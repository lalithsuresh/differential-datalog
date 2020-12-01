#!/bin/bash

set -ex

source ../build_java.sh
DDLFLAGS="--output-input-relations=O"
compile x.dl XTest.java debug
java -Djava.library.path=. XTest
cleanup
# Additional cleanup
# rm -rf x_ddlog
