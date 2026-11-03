// Package proto is the wire format of guts/1: one JSON body per HTTP request, forwarded
// unchanged by the door (a Google Apps Script web app) to the exit node.
//
// The door never interprets a request beyond its authentication key: it hands the body to the
// exit node and returns the exit node's answer. That keeps the door a dumb pipe, which is what
// makes the whole path survive a network that only lets a phone reach Google.
package proto

const Version = 1

// Ops of a request.
const (
	OpOpen  = "open"  // dial host:port, answer with a session id
	OpPush  = "push"  // write data to the session's socket
	OpPoll  = "poll"  // wait up to TimeoutMs for data from the socket
	OpClose = "close" // close the session
	OpPing  = "ping"  // liveness, answer with the exit node's build info
)

// Request is one guts/1 call.
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
const VersionTag = "0.1.0"

// VersionString names the protocol and this release.
func VersionString() string { return "guts/" + VersionTag }
