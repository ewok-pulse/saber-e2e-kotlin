#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import os
import re
import sys

dumps_location = "/tmp/cinterop-dumps-2"
commonized_line_pattern = re.compile(
    "^\\| (?:unsafe )?(?:int )?\\s*\\| (fun|val|var) ([a-zA-Z0-9_./:#<>]+)([:(].*) \\|"
)
platform_line_pattern = re.compile(
    "^\\| ([a-zA-Z0-9_]+|\\([a-zA-Z0-9_]+(?:, [a-zA-Z0-9_]+)+\\)) \\s*\\| (fun|val|var) ([a-zA-Z0-9_./:#<>]+)([:(].*) \\|"
)
tags = set(["unsafe", "int"])


def to_platform_set(platformString):
    cleared = (
        platformString.replace("+", "")
        .replace(",", "")
        .replace("(", "")
        .replace(")", "")
        .replace("  ", " ")
    )
    cleared = re.sub(" \\s+", " ", cleared)
    return frozenset(cleared.split(" "))


def gather_commonization_families():
    (_, libraries, _) = next(os.walk(dumps_location))

    debug_non_matched_lines = []

    platformDeclarationToCommonized = {}
    currentCommonized = None

    for library in libraries:
        print(f"Analyzing > {library}", file=sys.stderr)
        (_, _, commonizationFiles) = next(os.walk(f"{dumps_location}/{library}"))

        for commonizationFile in commonizationFiles:
            currentCommonizedPlatforms = to_platform_set(commonizationFile)

            with open(f"{dumps_location}/{library}/{commonizationFile}", "r") as file:
                lines = file.readlines()

                for line in lines:
                    match = commonized_line_pattern.match(line)

                    if match is not None:
                        currentCommonized = (
                            currentCommonizedPlatforms,
                            match.group(2),
                            match.group(3),
                            match.group(1),
                        )
                        continue

                    match = platform_line_pattern.match(line)

                    if match is None:
                        debug_non_matched_lines.append(line)
                        continue

                    platformString, name, signature, declarationKind = (
                        match.group(1),
                        match.group(3),
                        match.group(4),
                        match.group(2),
                    )

                    if platformString in tags:
                        continue

                    platforms = to_platform_set(platformString)

                    key = (platforms, name, signature, declarationKind)

                    if key[1] != currentCommonized[1]:
                        print(
                            f"Error > Wrong commonized name: key = {key[1]} vs currentCommonized = {currentCommonized[1]}"
                        )
                        exit(1)

                    platformDeclarationToCommonized[key] = currentCommonized

    finalCommonizedToPlatforms = {}

    for key, value in platformDeclarationToCommonized.items():
        finalCommonized = value

        while finalCommonized in platformDeclarationToCommonized:
            finalCommonized = platformDeclarationToCommonized[finalCommonized]

        if finalCommonized not in finalCommonizedToPlatforms:
            finalCommonizedToPlatforms[finalCommonized] = set()

        finalCommonizedToPlatforms[finalCommonized].add(key)

    return finalCommonizedToPlatforms
