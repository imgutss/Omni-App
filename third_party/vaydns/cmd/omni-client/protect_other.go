//go:build !unix

package main

import "fmt"

func protectSocketFD(_ string, _ uintptr) error {
	return fmt.Errorf("FD-control is unavailable on this platform")
}
