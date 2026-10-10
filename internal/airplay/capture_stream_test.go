package airplay

import (
	"errors"
	"io"
	"testing"
	"time"
)

func TestStreamSource(t *testing.T) {
	s := NewStreamSource()
	pts := time.Now()
	if err := s.Push([]byte{0, 0, 0, 1, 0x65}, pts); err != nil {
		t.Fatal(err)
	}
	au, err := s.AsCapture().ReadVideoAccessUnit()
	if err != nil || len(au.AnnexB) != 5 || !au.PTS.Equal(pts) {
		t.Fatalf("got %v, %v", au, err)
	}
	for i := 0; i < streamSourceQueue; i++ {
		_ = s.Push([]byte{1}, pts)
	}
	if err := s.Push([]byte{1}, pts); !errors.Is(err, errStreamSourceBacklog) {
		t.Fatalf("overflow err = %v", err)
	}
	s.Close()
	if err := s.Push([]byte{1}, pts); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("closed err = %v", err)
	}
}
