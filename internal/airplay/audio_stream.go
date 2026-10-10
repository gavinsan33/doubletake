package airplay

import (
	"fmt"
	"io"
	"sync"
	"time"
)

const (
	// pcmSourceMaxBuffered bounds queued PCM (2 s). Overflow drops the oldest
	// samples: a glitch is better than unbounded latency for live audio.
	pcmSourceMaxBuffered = 2 * audioSampleRate * audioBytesPerSampleFrame
	// pcmSourceReanchor is how far the arrival-time estimate may drift from the
	// sample-counted timeline before the timeline is re-anchored. Smaller
	// jitter is ignored so successive PTS values stay smooth.
	pcmSourceReanchor = 50 * time.Millisecond
)

// PCMSource is a push-fed audio capture for platforms where PCM is produced
// outside this process (e.g. Android AudioPlaybackCapture). The producer pushes
// interleaved 44.1 kHz stereo S16LE; the reader hands out fixed-size codec
// frames with the PTS of their first sample.
type PCMSource struct {
	mu     sync.Mutex
	cond   *sync.Cond
	buf    []byte
	next   time.Time // PTS of buf[0]
	have   bool
	closed bool
	done   chan struct{}
}

func NewPCMSource() *PCMSource {
	s := &PCMSource{done: make(chan struct{})}
	s.cond = sync.NewCond(&s.mu)
	return s
}

func pcmDuration(n int) time.Duration {
	return time.Duration(n/audioBytesPerSampleFrame) * time.Second / audioSampleRate
}

// Push queues PCM that finished arriving at now. Chunks must be whole sample frames.
func (s *PCMSource) Push(pcm []byte, now time.Time) error {
	if len(pcm)%audioBytesPerSampleFrame != 0 {
		return fmt.Errorf("pcm chunk of %d bytes is not whole stereo S16 frames", len(pcm))
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return io.ErrClosedPipe
	}
	est := now.Add(-pcmDuration(len(s.buf) + len(pcm)))
	if d := est.Sub(s.next); !s.have || d > pcmSourceReanchor || d < -pcmSourceReanchor {
		s.next, s.have = est, true
	}
	s.buf = append(s.buf, pcm...)
	if over := len(s.buf) - pcmSourceMaxBuffered; over > 0 {
		over -= over % audioBytesPerSampleFrame
		s.buf = s.buf[over:]
		s.next = s.next.Add(pcmDuration(over))
	}
	s.cond.Signal()
	return nil
}

func (s *PCMSource) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.closed {
		s.closed = true
		close(s.done)
		s.cond.Broadcast()
	}
}

// ReadPCMFrame blocks until len(dst) bytes are queued.
func (s *PCMSource) ReadPCMFrame(dst []byte) (time.Time, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for len(s.buf) < len(dst) && !s.closed {
		s.cond.Wait()
	}
	if len(s.buf) < len(dst) {
		return time.Time{}, io.EOF
	}
	n := copy(dst, s.buf)
	s.buf = s.buf[n:]
	pts := s.next
	s.next = s.next.Add(pcmDuration(n))
	return pts, nil
}

// NewPCMAudioCapture wraps src as an AudioCapture for MirrorSession.StreamAudio.
// ponytail: ALAC only; AAC-ELD needs the fdk encoder wired in.
func NewPCMAudioCapture(src *PCMSource, codec AudioCodec) (*AudioCapture, error) {
	if codec != AudioCodecALAC {
		return nil, fmt.Errorf("audio codec %d is not supported for pushed PCM", codec)
	}
	return &AudioCapture{cancel: src.Close, waitCh: src.done, codec: codec, alac: &alacEncoder{}, pcmFrames: src}, nil
}
