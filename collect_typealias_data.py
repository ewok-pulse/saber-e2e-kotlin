#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import os
import re

dumps_location = "/tmp/cinterop-dumps"
pattern = re.compile("^\\d+\\. (\\w+) <-(?: ([a-zA-Z0-9._]+) <-)? ([a-zA-Z0-9._]+)$")

(_, platforms, _) = next(os.walk(dumps_location))

direct_expansion = "<directly>"


def filter_lines_safe_unmatched(lines, debug_non_matched_lines, pattern):
    result = []

    for line in lines:
        match = pattern.match(line)

        if match is not None:
            result.append(match)
        else:
            debug_non_matched_lines.append(line)

    return result


def collect_typealiases():
    allTypeAliasesToIntegers = {}
    allTypeAliasesToExpansions = {}
    debug_non_matched_lines = []

    for platform in platforms:
        (_, _, libraries) = next(os.walk(f"{dumps_location}/{platform}"))
        typeAliasesToIntegers = {}
        typeAliasesToExpansions = {}

        for lib in libraries:
            with open(f"{dumps_location}/{platform}/{lib}", "r") as file:
                lines = file.readlines()
                lines = filter_lines_safe_unmatched(
                    lines, debug_non_matched_lines, pattern
                )
                lines = list(
                    map(lambda it: (it.group(1), it.group(2), it.group(3)), lines)
                )

            for integerType, expansion, typeAlias in lines:
                if (
                    typeAlias in typeAliasesToIntegers
                    or typeAlias in typeAliasesToExpansions
                ):
                    print(f"Error > Dublicate typealias in library {lib}")
                    exit(1)

                typeAliasesToIntegers[typeAlias] = integerType

                if expansion is None:
                    typeAliasesToExpansions[typeAlias] = direct_expansion
                else:
                    typeAliasesToExpansions[typeAlias] = expansion

        for typeAlias, integerType in typeAliasesToIntegers.items():
            if typeAlias not in allTypeAliasesToIntegers:
                allTypeAliasesToIntegers[typeAlias] = {}

            allTypeAliasesToIntegers[typeAlias][platform] = integerType

        for typeAlias, expansion in typeAliasesToExpansions.items():
            if typeAlias not in allTypeAliasesToExpansions:
                allTypeAliasesToExpansions[typeAlias] = {}

            allTypeAliasesToExpansions[typeAlias][platform] = expansion

    overlooked_lines = list(
        filter(
            lambda it: not (
                re.compile("^Typealiases to .*\n$").match(it)
                or re.compile("^- by \\d+ .* in their .*\n$").match(it)
                or re.compile("^Typealias .* is used...\n$").match(it)
                or re.compile("^Typealias usages are empty...\n$").match(it)
            ),
            debug_non_matched_lines,
        )
    )

    if len(overlooked_lines) > 0:
        print("Error > Found non-matched lines:")
        print("\n".join(overlooked_lines))
        exit(1)

    return allTypeAliasesToIntegers, allTypeAliasesToExpansions
