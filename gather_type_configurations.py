#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import re
import sys

from gather_allowed_platforms_data import gather_commonization_families
from parse_types import (
    parse_minimize,
    extract_inconsistencies_tree,
    TypesInSignature,
    drop_typealiases,
)

finalCommonizedToPlatforms = gather_commonization_families()

commonizationTypeConfigurationsToUsageCount = {}

for finalCommonized, implementations in finalCommonizedToPlatforms.items():
    platforms = finalCommonized[0]
    typeConfigurations = {}

    for implementation in implementations:
        if len(implementation[0]) > 1:
            continue

        platform = list(implementation[0])[0]

        TYPE_MARKER = ",: "

        clearedSignature = implementation[2]
        clearedSignature = (
            clearedSignature.replace("): ", TYPE_MARKER)
            .replace("(", "")
            .replace(")", "")
        )
        clearedSignature = re.sub(
            "(?:^|, )(?:<#0>\\.)?[a-zA-Z0-9_]+: ", TYPE_MARKER, clearedSignature
        )
        typesInImplementation = list(
            filter(lambda it: it != "", clearedSignature.split(TYPE_MARKER))
        )

        typeConfigurations[platform] = TypesInSignature(
            name=implementation[1],
            types=list(map(lambda it: parse_minimize(it), typesInImplementation)),
        )

    inconsistencies = extract_inconsistencies_tree(typeConfigurations)

    for inconsistency in inconsistencies:
        typeConfiguration = {
            platform: str(drop_typealiases(value))
            for platform, value in inconsistency.items()
        }

        types = set(typeConfiguration.values())
        # Only collect inconsistent ones
        if len(types) < 2:
            continue

        frozen = frozenset(typeConfiguration.items())

        if frozen not in commonizationTypeConfigurationsToUsageCount:
            commonizationTypeConfigurationsToUsageCount[frozen] = 0

        commonizationTypeConfigurationsToUsageCount[frozen] += 1


def firstTypeConfigurationSupercedesSecond(configuration1, configuration2):
    map1 = {x: y for x, y in configuration1}
    map2 = {x: y for x, y in configuration2}

    if any([it not in map1 for it in map2]):
        return False

    for it in map2:
        if map1[it] != map2[it]:
            return False

    return True


leafCommonizationTypeConfigurations = set()
squashedCommonizationTypeConfigurationsToUsageCount = {}

# Squash "subconfigurations"
for index, (typeConfiguration, count) in enumerate(
    commonizationTypeConfigurationsToUsageCount.items()
):
    isLeaf = True

    for subIndex, (existingTypeConfiguration, existingCount) in enumerate(
        commonizationTypeConfigurationsToUsageCount.items()
    ):
        if index == subIndex:
            continue

        if firstTypeConfigurationSupercedesSecond(
            existingTypeConfiguration, typeConfiguration
        ):
            isLeaf = False

    if isLeaf:
        leafCommonizationTypeConfigurations.add(typeConfiguration)

index = 0
for configuration, usageCount in sorted(
    commonizationTypeConfigurationsToUsageCount.items(),
    key=lambda it: it[1],
    reverse=True,
):
    if configuration not in leafCommonizationTypeConfigurations:
        continue

    index += 1
    print(f"{index}. {usageCount} usages")

    maxIndexLength = len(str(len(configuration) - 1))
    subindex = 0
    for platform, type in configuration:
        subindex += 1
        print(f"  {str(subindex).rjust(maxIndexLength)}. {type} ({platform})")
