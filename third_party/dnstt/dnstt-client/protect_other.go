//go:build !unix

package main

import "fmt"

func protectFD(fd uintptr) error {
	return fmt.Errorf("fd protection is unsupported on this platform")
}
