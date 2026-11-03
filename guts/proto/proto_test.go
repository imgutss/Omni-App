package proto

import (
	"bytes"
	"testing"
)

func TestFramesSurviveOneRoundTrip(t *testing.T) {
	frames := []Frame{
		{S: 1, D: []byte("hello")},
		{S: 2, D: bytes.Repeat([]byte("x"), 100)},
		{S: 3, EOF: true},
		{S: 7, D: []byte{0, 1, 2}},
	}

	body := EncodeFrames(frames)
	back, err := DecodeFrames(body)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(back) != len(frames) {
		t.Fatalf("got %d frames, want %d", len(back), len(frames))
	}
	for i, want := range frames {
		got := back[i]
		if got.S != want.S || got.EOF != want.EOF || !bytes.Equal(got.D, want.D) {
			t.Fatalf("frame %d = %+v, want %+v", i, got, want)
		}
	}
}

func TestFramesKeepTheirOrder(t *testing.T) {
	// The phone appends whatever arrives to the stream it names, so the order of frames for one
	// stream is the order of its bytes.
	frames := []Frame{{S: 1, D: []byte("one")}, {S: 2, D: []byte("two")}, {S: 1, D: []byte("three")}}
	back, err := DecodeFrames(EncodeFrames(frames))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	var first []byte
	for _, f := range back {
		if f.S == 1 {
			first = append(first, f.D...)
		}
	}
	if string(first) != "onethree" {
		t.Fatalf("stream 1 got %q", first)
	}
}

func TestACutFrameIsAnErrorNotSilence(t *testing.T) {
	body := EncodeFrames([]Frame{{S: 1, D: []byte("payload")}})
	for _, cut := range [][]byte{body[:3], body[:len(body)-1]} {
		if _, err := DecodeFrames(cut); err == nil {
			t.Fatalf("a cut body of %d bytes decoded without an error", len(cut))
		}
	}
}

func TestBytesAndTheEndTravelTogether(t *testing.T) {
	// Last bytes of a stream and its end arrive in one answer: the bytes must not be lost behind
	// the end, and the end must not arrive before them.
	body := EncodeFrames([]Frame{{S: 4, D: []byte("last words"), EOF: true}})
	back, err := DecodeFrames(body)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(back) != 2 {
		t.Fatalf("got %d frames, want 2 (bytes, then end)", len(back))
	}
	if back[0].S != 4 || string(back[0].D) != "last words" || back[0].EOF {
		t.Fatalf("first frame = %+v", back[0])
	}
	if back[1].S != 4 || !back[1].EOF || len(back[1].D) != 0 {
		t.Fatalf("second frame = %+v", back[1])
	}
}

func TestAnEmptyBodyHoldsNoFrames(t *testing.T) {
	frames, err := DecodeFrames(nil)
	if err != nil || len(frames) != 0 {
		t.Fatalf("frames=%v err=%v", frames, err)
	}
}
