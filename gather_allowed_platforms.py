#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import os
import re
import sys

from gather_allowed_platforms_data import gather_commonization_families

finalCommonizedToPlatforms = gather_commonization_families()

index = 0
for finalCommonized, mapping in finalCommonizedToPlatforms.items():
    index += 1

    print(
        f'{index}. fun {finalCommonized[1]}{finalCommonized[2]} ({", ".join(finalCommonized[0])})'
    )
    subIndex = 0

    for implementation in mapping:
        if len(implementation[0]) > 1:
            continue

        subIndex += 1
        print(
            f'  {subIndex}. fun {implementation[1]}{implementation[2]} ({", ".join(implementation[0])})'
        )
