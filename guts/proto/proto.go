// Package proto is the wire format of guts/1: one JSON body per HTTP request, forwarded
// unchanged by the door (a Google Apps Script web app) to the exit node.
//
// The door never interprets a request beyond its authentication key: it hands the body to the
// exit node and returns the exit node's answer. That keeps the door a dumb pipe, which is what
// makes the whole path survive a network that only lets a phone reach Google.
package proto

import (
	"encoding/binary"
	"errors"
)

const Version = 1

// MuxCapability is what an exit node that can multiplex answers on the open of a new session. The
// phone reads it and falls back to one session per connection when it is missing: an exit node
// deployed before multiplexing still works, and still costs one call per connection, which is
// exactly what the phone must say out loud rather than pretend.
const MuxCapability = "mux"

// MuxCapabilityV2 is answered by an exit node whose mux open also carries the stream's first
// bytes (the request's D): the phone bundles the ClientHello it is already holding with the
// open, which saves one whole door round-trip per connection. A node that answers plain "mux"
// ignores D on open, so the phone re-sends those bytes as an ordinary push.
const MuxCapabilityV2 = "mux2"

// Ops of a request.
const (
	OpOpen  = "open"  // dial host:port, answer with a session id
	OpPush  = "push"  // write data to the session's socket
	OpPoll  = "poll"  // wait up to TimeoutMs for data from the socket
	OpClose = "close" // close the session
	OpPing  = "ping"  // liveness, answer with the exit node's build info
)

// Request is one guts/1 call.
//
// A multiplexed session carries many streams, one per connection the phone opens, over one session
// and therefore over one poll per interval instead of one per connection: that is what makes an
// Apps Script quota last. S names the stream, MU marks the session as multiplexed.
type Request struct {
	V    int    `json:"v"`
	K    string `json:"k,omitempty"`    // shared key, checked by the door and by the exit node
	Op   string `json:"op"`             //
	Sid  string `json:"sid,omitempty"`  // session id, for every op but open and ping
	Host string `json:"host,omitempty"` // open: destination
	Port int    `json:"port,omitempty"` // open: destination
	D    string `json:"d,omitempty"`    // push: base64 bytes towards the destination
	T    int    `json:"t,omitempty"`    // poll: how long the exit node may wait, in milliseconds
	N    int    `json:"n,omitempty"`    // poll: most bytes to answer with
	S    uint32 `json:"s,omitempty"`    // mux: stream id inside the session
	MU   bool   `json:"mu,omitempty"`   // open/push/poll/close: the session carries many streams
}

// Response is the answer of one guts/1 call.
type Response struct {
	OK    bool   `json:"ok"`
	Sid   string `json:"sid,omitempty"`
	D     string `json:"d,omitempty"` // poll: base64 bytes from the destination
	EOF   bool   `json:"eof,omitempty"`
	Error string `json:"e,omitempty"`
	Info  string `json:"info,omitempty"`
}

// Mux frames. A poll answer of a multiplexed session is a sequence of frames, each one a stream id,
// a length, and that stream's bytes; a length of FrameEOF says that stream ended. The exit node
// therefore answers every stream of the session with one door call, and the phone pays one call per
// interval no matter how many connections it holds.
const (
	FrameHeader = 8
	FrameEOF    = ^uint32(0)
)

// Frame is one stream's piece of a multiplexed poll answer.
type Frame struct {
	S   uint32
	D   []byte
	EOF bool
}

// EncodeFrames writes frames into one body.
//
// A frame whose stream both carries bytes and ends becomes two frames — the bytes, then the end —
// because the wire format says one thing per frame, and dropping either would lose data or hide
// the end of a stream. The caller decides how many bytes fit in one answer.
func EncodeFrames(frames []Frame) []byte {
	out := make([]byte, 0, 256)
	for _, f := range frames {
		if len(f.D) > 0 {
			out = binary.BigEndian.AppendUint32(out, f.S)
			out = binary.BigEndian.AppendUint32(out, uint32(len(f.D)))
			out = append(out, f.D...)
		}
		if f.EOF {
			out = binary.BigEndian.AppendUint32(out, f.S)
			out = binary.BigEndian.AppendUint32(out, FrameEOF)
		}
	}
	return out
}

// DecodeFrames reads a poll body back into frames.
func DecodeFrames(body []byte) ([]Frame, error) {
	var frames []Frame
	for len(body) > 0 {
		if len(body) < FrameHeader {
			return frames, errors.New("guts: a frame header was cut short")
		}
		id := binary.BigEndian.Uint32(body[:4])
		length := binary.BigEndian.Uint32(body[4:8])
		body = body[FrameHeader:]
		if length == FrameEOF {
			frames = append(frames, Frame{S: id, EOF: true})
			continue
		}
		if int(length) > len(body) {
			return frames, errors.New("guts: a frame payload was cut short")
		}
		frames = append(frames, Frame{S: id, D: body[:length]})
		body = body[length:]
	}
	return frames, nil
}

// Defaults that both sides keep, so a request that leaves a field out still behaves the same.
const (
	DefaultPollMs   = 8_000 // a short poll, used while data is flowing
	IdlePollMs      = 20_000
	MaxPollMs       = 25_000
	DefaultPollMax  = 128 * 1024
	MaxPollMax      = 512 * 1024
	MaxPushBytes    = 256 * 1024
	SessionIdleSecs = 90
)

// VersionTag is the release of this implementation, reported by a ping.
const VersionTag = "0.3.0"

// VersionString names the protocol and this release.
func VersionString() string { return "guts/" + VersionTag }
