#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import os
import re

dumps_location = "/tmp/cinterop-dumps"
pattern = re.compile("^\\d+\\. (\\w+) <-(?: ([a-zA-Z0-9._]+) <-)? ([a-zA-Z0-9._]+)$")

(_, platforms, _) = next(os.walk(dumps_location))

allTypeAliasesToIntegers = {}
allTypeAliasesToExpansions = {}

direct_expansion = "<directly>"

debug_non_matched_lines = []


def filter_lines_safe_unmatched(lines):
    result = []

    for line in lines:
        match = pattern.match(line)

        if match is not None:
            result.append(match)
        else:
            debug_non_matched_lines.append(line)

    return result


for platform in platforms:
    (_, _, libraries) = next(os.walk(f"{dumps_location}/{platform}"))
    typeAliasesToIntegers = {}
    typeAliasesToExpansions = {}

    for lib in libraries:
        with open(f"{dumps_location}/{platform}/{lib}", "r") as file:
            lines = file.readlines()
            lines = filter_lines_safe_unmatched(lines)
            lines = list(map(lambda it: (it.group(1), it.group(2), it.group(3)), lines))

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
            allTypeAliasesToIntegers[typeAlias] = set()

        allTypeAliasesToIntegers[typeAlias].add(integerType)

    for typeAlias, expansion in typeAliasesToExpansions.items():
        if typeAlias not in allTypeAliasesToExpansions:
            allTypeAliasesToExpansions[typeAlias] = set()

        allTypeAliasesToExpansions[typeAlias].add(expansion)

found_any_overlooked_lines = False

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

# for typeAlias, expansions in allTypeAliasesToExpansions.items():
#     if len(expansions) != 1:
#         print(f'Error > Typealias {typeAlias} has inconsistent expansions: {expansions} for integers {allTypeAliasesToIntegers[typeAlias]}')
#         exit(1)

print(f"Consistent typealiases:")
index = 1

for typeAlias, integerTypes in allTypeAliasesToIntegers.items():
    if len(integerTypes) == 1:
        print(f"{index}. {list(integerTypes)[0]} <- {typeAlias}")
        index += 1


def dump_inconsistent(condition, message, printExpansion=True):
    print(message)
    index = 1

    for typeAlias, integerTypes in allTypeAliasesToIntegers.items():
        expansions = allTypeAliasesToExpansions[typeAlias]

        if len(integerTypes) != 1 and condition(expansions):
            expansionInfix = (
                (" | ".join(allTypeAliasesToExpansions[typeAlias]) + " <- ")
                if printExpansion
                else ""
            )
            print(f'{index}. {" | ".join(integerTypes)} <- {expansionInfix}{typeAlias}')
            index += 1


dump_inconsistent(
    lambda expansions: len(expansions) == 1 and list(expansions)[0] == direct_expansion,
    f"Inconsistent leaf typealiases:",
    printExpansion=False,
)

dump_inconsistent(
    lambda expansions: len(expansions) == 1 and list(expansions)[0] != direct_expansion,
    f"Inconsistent non-leaf typealiases with common immediate expansion:",
)

dump_inconsistent(
    lambda expansions: len(expansions) != 1
    and all([it != direct_expansion for it in expansions]),
    f"Inconsistent non-leaf typealiases with complex indirect expansions:",
)

dump_inconsistent(
    lambda expansions: len(expansions) != 1
    and any([it == direct_expansion for it in expansions]),
    f"Inconsistent non-leaf typealiases with complex direct expansions:",
)
