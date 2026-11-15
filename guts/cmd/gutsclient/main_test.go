package main

import (
	"reflect"
	"testing"
)

func TestEdgeListSplitsAndDeduplicatesManualAddresses(t *testing.T) {
	got := edgeList("216.239.38.120, 142.250.72.14\n216.239.38.120;172.217.16.142\t[2001:4860:4860::8888]")
	want := []string{"216.239.38.120", "142.250.72.14", "172.217.16.142", "[2001:4860:4860::8888]"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("edgeList = %v, want %v", got, want)
	}
}

func TestEdgeListLeavesAutoAsOneToken(t *testing.T) {
	if got := edgeList("auto"); len(got) != 1 || got[0] != "auto" {
		t.Fatalf("edgeList(auto) = %v", got)
	}
}
