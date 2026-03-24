#!/bin/bash
#
# Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
# Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
#

set -e
JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=5006" "$@"

