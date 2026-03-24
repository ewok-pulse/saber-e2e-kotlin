#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

from collect_typealias_data import collect_typealiases, direct_expansion

allTypeAliasesToIntegers, allTypeAliasesToExpansions = collect_typealiases()

for alias, platformToInteger in allTypeAliasesToIntegers.items():
    allTypeAliasesToIntegers[alias] = set(platformToInteger.values())

for alias, platformToExpansion in allTypeAliasesToExpansions.items():
    allTypeAliasesToExpansions[alias] = set(platformToExpansion.values())

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
