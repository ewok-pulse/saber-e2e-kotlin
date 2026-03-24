#!/bin/bash
set -e
#JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=5006" "$@"
#GRADLE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=n,suspend=y,address=5006" "$@"
#GRADLE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=n,suspend=y,address=5006" "$@" $(disable-kotlin-daemon)
"$@" '-Pkotlin.mpp.commonizerJvmArgs=-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=5006'
