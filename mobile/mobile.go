// Package mobile is the gomobile entry point for the Android app. The platform
// owns screen capture and H.264 encoding; this package owns everything on the
// wire (pairing, FairPlay, RTSP, encryption, timing).
package mobile

import (
	"context"
	"errors"
	"fmt"
	"log"
	"path/filepath"
	"sync"
	"time"

	"doubletake/internal/airplay"
)

// Host is implemented by the app.
type Host interface {
	// StartEncoder is called mid-SETUP with the canvas the receiver wants.
	// After it returns, the app pushes access units with PushFrame.
	StartEncoder(width, height int) error
	// RequestCode asks the user for the on-screen PIN or the receiver's
	// configured password. Return "" to cancel.
	RequestCode(prompt string) string
	Log(msg string)
}

var (
	mu     sync.Mutex
	cancel context.CancelFunc
	source *airplay.StreamSource
)

// Run connects to the receiver and mirrors until Stop or a stream error.
// It blocks; call it from a worker thread. credsDir persists pairing keys.
// ponytail: one session per process, enough for a phone.
func Run(host Host, addr string, port, fps, bitrateKbps int, credsDir, code string) error {
	ctx, stop := context.WithCancel(context.Background())
	src := airplay.NewStreamSource()
	mu.Lock()
	if cancel != nil {
		mu.Unlock()
		stop()
		return errors.New("a session is already running")
	}
	cancel, source = stop, src
	mu.Unlock()
	defer func() {
		src.Close()
		stop()
		mu.Lock()
		cancel, source = nil, nil
		mu.Unlock()
	}()
	log.SetOutput(logWriter{host})
	return run(ctx, host, src, addr, port, fps, bitrateKbps, credsDir, code)
}

type logWriter struct{ h Host }

func (w logWriter) Write(p []byte) (int, error) { w.h.Log(string(p)); return len(p), nil }

// PushFrame queues one complete Annex-B access unit from the encoder
// (SPS/PPS prepended to every IDR). A non-nil error means the stream is dead.
func PushFrame(annexB []byte) error {
	mu.Lock()
	src := source
	mu.Unlock()
	if src == nil {
		return errors.New("no active session")
	}
	return src.Push(annexB, time.Now())
}

// Stop ends the running session, if any.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func run(ctx context.Context, host Host, src *airplay.StreamSource, addr string, port, fps, bitrateKbps int, credsDir, code string) error {
	newClient := func() (*airplay.AirPlayClient, error) {
		c := airplay.NewAirPlayClient(addr, port)
		c.SetPassword(code)
		if err := c.Connect(ctx); err != nil {
			return nil, fmt.Errorf("connect: %w", err)
		}
		return c, nil
	}
	client, err := newClient()
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }()
	info, err := client.GetInfo()
	if err != nil {
		return fmt.Errorf("get info: %w", err)
	}
	ask := func(prompt string) (string, error) {
		v := host.RequestCode(prompt)
		if v == "" {
			return "", errors.New("cancelled")
		}
		code = v
		client.SetPassword(v)
		return v, nil
	}
	if code == "" && info.RequiresPassword() {
		if _, err := ask("Enter the receiver's configured password"); err != nil {
			return err
		}
	}
	store, err := airplay.NewCredentialStore(filepath.Join(credsDir, "credentials.json"))
	if err != nil {
		return fmt.Errorf("credentials: %w", err)
	}
	reconnect := func() error {
		_ = client.Close()
		c, err := newClient()
		if err != nil {
			return err
		}
		client = c
		info, err = client.GetInfo()
		return err
	}
	// pairWithCode runs full pair-setup with a user-entered PIN/password and saves the keys.
	pairWithCode := func(expectPIN bool) error {
		prompt := "Enter the receiver's configured password"
		if expectPIN {
			if err := client.StartPINDisplay(); err != nil {
				log.Printf("PIN display: %v", err)
			}
			prompt = "Enter the PIN shown on the receiver"
		}
		v := code
		if v == "" {
			var err error
			if v, err = ask(prompt); err != nil {
				return err
			}
		}
		if err := client.Pair(ctx, v); err != nil {
			return fmt.Errorf("pairing: %w", err)
		}
		if client.PairKeys != nil {
			if err := store.SavePairing(info.DeviceID, client.PairingID, client.PairKeys.Ed25519Public,
				client.PairKeys.Ed25519Private, client.PairingProtocol()); err != nil {
				log.Printf("save credentials: %v", err)
			}
		}
		return nil
	}
	pairFresh := func() error {
		switch {
		case info.RequiredPairingCredential() == airplay.PairingCredentialPassword:
			return pairWithCode(false)
		case info.RequiredPairingCredential() == airplay.PairingCredentialPIN:
			return pairWithCode(true)
		}
		if err := client.Pair(ctx, ""); err != nil {
			if info.RequiresPassword() {
				return fmt.Errorf("transient pairing: %w", err)
			}
			log.Printf("transient pairing failed: %v; requesting credentials", err)
			if err := reconnect(); err != nil {
				return err
			}
			return pairWithCode(false)
		}
		return nil
	}

	saved := store.Lookup(info.DeviceID)
	if saved != nil && saved.HasPairingCredentials() {
		err := client.RestorePairingCredentials(saved)
		if err == nil {
			err = client.PairVerify(ctx)
		}
		if err != nil {
			log.Printf("pair-verify with saved credentials failed: %v", err)
			if err := reconnect(); err != nil {
				return err
			}
			if err := pairFresh(); err != nil {
				return err
			}
		}
	} else if err := pairFresh(); err != nil {
		return err
	}

	if client.FpEkey == nil {
		if err := client.FairPlaySetup(ctx); err != nil && !errors.Is(err, airplay.ErrFairPlayUnsupported) {
			return fmt.Errorf("FairPlay setup: %w", err)
		}
	}

	// ponytail: H.264 only, no audio; add HEVC/audio when the encoder side supports them.
	cfg := airplay.StreamConfig{FPS: fps, Bitrate: bitrateKbps, VideoCodec: airplay.VideoCodecH264, NoAudio: true}
	started := false
	prepare := func(w, h int, codec airplay.VideoCodec) (airplay.VideoPreparationResult, error) {
		if !started {
			if err := host.StartEncoder(w, h); err != nil {
				return airplay.VideoPreparationResult{}, err
			}
			started = true
		}
		return airplay.VideoPreparationResult{}, nil
	}
	session, err := client.SetupMirrorWithCalibratedVideoPreparation(ctx, cfg, prepare)
	if errors.Is(err, airplay.ErrCredentialsRequired) {
		// Legacy receivers only reveal a configured password via the SETUP challenge.
		if _, err := ask("Enter the receiver's configured password"); err != nil {
			return err
		}
		session, err = client.SetupMirrorWithCalibratedVideoPreparation(ctx, cfg, prepare)
		if err != nil {
			return fmt.Errorf("mirror setup: %w", err)
		}
	} else if err != nil {
		return fmt.Errorf("mirror setup: %w", err)
	}
	defer session.Close()
	log.Printf("mirror session ready (data port %d)", session.DataPort)

	capture := src.AsCapture()
	go func() { <-ctx.Done(); capture.Stop(); session.Close() }()
	if err := session.StreamFrames(ctx, capture, 0); err != nil && ctx.Err() == nil {
		return fmt.Errorf("streaming: %w", err)
	}
	return nil
}
