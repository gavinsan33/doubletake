package airplay

import (
	"errors"
	"io"
	"testing"
	"time"
)

func TestPCMSourceTimeline(t *testing.T) {
	s := NewPCMSource()
	t0 := time.Now()
	chunk := make([]byte, 441*audioBytesPerSampleFrame) // 10 ms
	// Jittery arrivals (±10 ms) must not disturb the sample-counted timeline.
	for i := 0; i < 10; i++ {
		jitter := time.Duration(i%3-1) * 10 * time.Millisecond
		if err := s.Push(chunk, t0.Add(time.Duration(i+1)*10*time.Millisecond+jitter)); err != nil {
			t.Fatal(err)
		}
	}
	frame := make([]byte, 352*audioBytesPerSampleFrame)
	p1, err := s.ReadPCMFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	p2, _ := s.ReadPCMFrame(frame)
	if d := p2.Sub(p1); d != pcmDuration(len(frame)) {
		t.Fatalf("frame spacing %v, want %v", d, pcmDuration(len(frame)))
	}
	// A large clock jump re-anchors.
	_ = s.Push(chunk, t0.Add(10*time.Second))
	if p, _ := s.ReadPCMFrame(make([]byte, 4)); p.Before(t0.Add(5 * time.Second)) {
		t.Fatalf("expected re-anchor near +10s, got %v", p.Sub(t0))
	}
}

func TestPCMSourceOverflowAndClose(t *testing.T) {
	s := NewPCMSource()
	now := time.Now()
	big := make([]byte, pcmSourceMaxBuffered)
	_ = s.Push(big, now)
	_ = s.Push(make([]byte, 4000), now)
	if len(s.buf) != pcmSourceMaxBuffered {
		t.Fatalf("buffered %d, want cap %d", len(s.buf), pcmSourceMaxBuffered)
	}
	if err := s.Push([]byte{1, 2, 3}, now); err == nil {
		t.Fatal("expected error for partial sample frame")
	}
	go func() { time.Sleep(10 * time.Millisecond); s.Close() }()
	for {
		if _, err := s.ReadPCMFrame(make([]byte, 1408)); err != nil {
			if !errors.Is(err, io.EOF) {
				t.Fatal(err)
			}
			break
		}
	}
	if err := s.Push(make([]byte, 4), now); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("push after close: %v", err)
	}
}

func TestPCMAudioCaptureEncodes(t *testing.T) {
	s := NewPCMSource()
	ac, err := NewPCMAudioCapture(s, AudioCodecALAC)
	if err != nil {
		t.Fatal(err)
	}
	_ = s.Push(make([]byte, 352*audioBytesPerSampleFrame), time.Now())
	buf := make([]byte, 8192)
	n, pts, err := ac.ReadFrameAt(buf)
	if err != nil || n == 0 || pts.IsZero() {
		t.Fatalf("n=%d pts=%v err=%v", n, pts, err)
	}
	ac.Stop() // must not hang without a gst process
	if _, err := NewPCMAudioCapture(s, AudioCodecAACELD); err == nil {
		t.Fatal("ELD should be rejected")
	}
}
