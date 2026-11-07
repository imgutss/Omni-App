//go:build !unix

package mobile

import "fmt"

func protectFD(fd uintptr) error {
	return fmt.Errorf("fd protection is unsupported on this platform")
}
