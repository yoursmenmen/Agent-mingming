#!/usr/bin/env bash
set -e

export JAVA_HOME="C:/Env/Java/Java21"
export PATH="$JAVA_HOME/bin:$PATH"

mvn "$@"
