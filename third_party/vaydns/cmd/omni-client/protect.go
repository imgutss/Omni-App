package main

import (
	"fmt"
	"syscall"
)

type socketControlFunc = func(network, address string, raw syscall.RawConn) error

func socketControl(address string) socketControlFunc {
	if address == "" {
		return nil
	}
	return func(_, _ string, raw syscall.RawConn) error {
		var result error
		if err := raw.Control(func(fd uintptr) {
			result = protectSocketFD(address, fd)
		}); err != nil {
			return err
		}
		if result != nil {
			return fmt.Errorf("Android socket protection: %w", result)
		}
		return nil
	}
}
