package mobile

import (
	"context"
	"net"
	"testing"
	"time"

	"doubletake/internal/airplay"
)

type fakeHost struct {
	t       *testing.T
	w, h    int
	started chan struct{}
}

func (f *fakeHost) StartEncoder(w, h int) error {
	f.w, f.h = w, h
	go func() {
		// 1920x1080 High-profile SPS, PPS, then an IDR-shaped payload and P-frames.
		sps := []byte{0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44, 0, 0, 3, 0, 4, 0, 0, 3, 0, 0xf0, 0x3c, 0x60, 0xc6, 0x58}
		pps := []byte{0, 0, 0, 1, 0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0}
		for i := 0; ; i++ {
			frame := append(append([]byte{}, sps...), pps...)
			frame = append(frame, 0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, byte(i))
			if i%30 != 0 {
				frame = []byte{0, 0, 0, 1, 0x41, 0x9a, 0x00, byte(i)}
			}
			if PushFrame(frame) != nil {
				return
			}
			time.Sleep(33 * time.Millisecond)
		}
	}()
	close(f.started)
	return nil
}
func (f *fakeHost) StartAudio() error {
	go func() {
		chunk := make([]byte, 441*4) // 10 ms of silence
		for PushAudio(chunk) == nil {
			time.Sleep(10 * time.Millisecond)
		}
	}()
	return nil
}
func (f *fakeHost) RequestCode(string) string { return "" }
func (f *fakeHost) Log(msg string)            { f.t.Log(msg) }

func TestRunMirrorsToReceiver(t *testing.T) {
	srv, err := airplay.NewReceiverServer(airplay.ReceiverConfig{
		ListenAddress: "127.0.0.1:0", Profile: airplay.ReceiverProfileModern, Auth: airplay.ReceiverAuthNone,
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	go srv.Serve(ctx)
	defer srv.Close()
	_, portStr, _ := net.SplitHostPort(srv.Addr().String())
	port, _ := net.LookupPort("tcp", portStr)

	host := &fakeHost{t: t, started: make(chan struct{})}
	done := make(chan error, 1)
	go func() { done <- Run(host, "127.0.0.1", port, 30, 4000, true, t.TempDir(), "") }()

	deadline := time.After(20 * time.Second)
	for st := srv.Stats(); st.VideoPackets < 5 || st.AudioPackets < 5; st = srv.Stats() {
		select {
		case err := <-done:
			t.Fatalf("Run ended early: %v (stats %+v)", err, srv.Stats())
		case <-deadline:
			t.Fatalf("no video reached receiver: %+v", srv.Stats())
		case <-time.After(50 * time.Millisecond):
		}
	}
	Stop()
	if err := <-done; err != nil {
		t.Fatalf("Run: %v", err)
	}
	if st := srv.Stats(); st.VideoCryptoErrors != 0 || st.VideoWidth != 1920 {
		t.Fatalf("stats: %+v", st)
	}
	t.Logf("encoder requested at %dx%d", host.w, host.h)
}
