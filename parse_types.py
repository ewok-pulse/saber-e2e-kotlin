#  Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
#  Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

import re
import sys

IDENTIFIER_SYMBOLS = (
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789/.#"
)


class ParsingContext:
    def __init__(self, input, index=0):
        self.index = index
        self.input = input

    def save(self):
        return self.index

    def restore(self, saved):
        self.index = saved

    def peek(self, n=1):
        end = min(self.index + n, len(self.input))
        return self.input[self.index : end]

    def read(self, n=1):
        result = self.peek(n=n)
        self.index += len(result)
        return result

    def expect(self, text):
        return self.peek(n=len(text)) == text

    def accept(self, text):
        if self.expect(text):
            self.index += len(text)
            return True
        return False


def parse_identifier(context):
    result = ""

    if context.accept("<#0>"):
        result += "<#0>"

    while context.peek() != "" and context.peek() in IDENTIFIER_SYMBOLS:
        result += context.read()

    if result == "":
        return None

    return result


class TypeNode:
    def __init__(self, classifier, arguments, is_nullable):
        self.classifier = classifier
        self.arguments = arguments
        self.is_nullable = is_nullable

    def fmap(self, transform):
        return TypeNode(
            classifier=self.classifier,
            arguments=list(map(transform, self.arguments)),
            is_nullable=self.is_nullable,
        )

    def __str__(self):
        if len(self.arguments) != 0:
            arguments = ", ".join(map(lambda it: str(it), self.arguments))
            arguments_marker = f"<{arguments}>"
        else:
            arguments_marker = ""

        nullable_marker = f"?" if self.is_nullable else ""
        return f"{self.classifier}{arguments_marker}{nullable_marker}"

    def __repr__(self):
        return self.__str__()

    def is_shallowly_equal(self, other):
        """
        Means a further `destruct()` call makes sense.
        """
        return (
            isinstance(other, TypeNode)
            and self.classifier == other.classifier
            and self.is_nullable == other.is_nullable
        )

    def destruct(self):
        return self.arguments


def parse_type(context):
    start = context.save()
    identifier = parse_identifier(context)

    if not identifier:
        return None

    result = TypeNode(classifier=identifier, arguments=[], is_nullable=False)

    if context.accept("<"):
        while True:
            next = parse_type_projection(context)

            if next is None:
                context.restore(start)
                return None

            result.arguments.append(next)

            if context.accept(">"):
                break

            if context.accept(", "):
                continue

            context.restore(start)
            return None

    if context.accept("?"):
        result.is_nullable = True

    return result


class TypeWithExpansionNode:
    def __init__(self, types):
        self.types = types

    def fmap(self, transform):
        return TypeWithExpansionNode(types=list(map(transform, self.types)))

    def __str__(self):
        return " -> ".join(map(lambda it: str(it), self.types))

    def __repr__(self):
        return self.__str__()

    def is_shallowly_equal(self, other):
        print(
            f"Error > is_shallowly_equal() should never be called on TypeWithExpansionNode"
        )
        exit(10)

    def destruct(self):
        return self.types[-1].destruct()


class TypeProjectionNode:
    def __init__(self, kind, type):
        self.kind = kind
        self.type = type

    def __str__(self):
        if self.kind == "*":
            return "*"

        if self.kind == None:
            return str(self.type)

        return f"{self.kind} {self.type}"

    def __repr__(self):
        return self.__str__()

    def fmap(self, transform):
        return TypeProjectionNode(kind=self.kind, type=transform(self.type))

    def is_shallowly_equal(self, other):
        return isinstance(other, TypeProjectionNode) and self.kind == other.kind

    def destruct(self):
        if self.kind == "*":
            return []

        return [self.type]


def parse_type_with_expansion(context):
    start = context.save()
    type = parse_type(context)

    if not type:
        return None

    result = TypeWithExpansionNode(types=[type])

    while context.accept(" -> "):
        expansion = parse_type(context)

        if not expansion:
            context.restore(start)
            return None

        result.types.append(expansion)

    if len(result.types) == 1:
        return type

    return result


def parse_type_projection(context):
    if context.accept("*"):
        return TypeProjectionNode(kind="*", type=None)

    start = context.save()

    if context.accept("out "):
        kind = "out"
    elif context.accept("in "):
        kind = "in"
    else:
        kind = None

    further_type = parse_type_with_expansion(context)

    if further_type is None:
        context.restore(start)
        return None

    return TypeProjectionNode(kind=kind, type=further_type)


def unwrap_typealias(tree):
    if isinstance(tree, TypeWithExpansionNode):
        return tree.types[-1]

    return tree


def drop_typealiases(tree):
    if isinstance(tree, TypeWithExpansionNode):
        return drop_typealiases(unwrap_typealias(tree))

    if isinstance(tree, TypeNode):
        return tree.fmap(drop_typealiases)

    if isinstance(tree, TypesInSignature):
        return tree.fmap(drop_typealiases)

    if isinstance(tree, TypeProjectionNode):
        return tree.fmap(drop_typealiases)

    return tree


class TypesInSignature:
    def __init__(self, name, signature, kind, types):
        self.name = name
        self.signature = signature
        self.kind = kind
        self.types = types

    def __str__(self):
        return (
            f"{self.name}("
            + ", ".join(map(lambda it: str(it), self.types[:-1]))
            + "): "
            + str(self.types[-1])
        )

    def __repr__(self):
        return self.__str__()

    def fmap(self, transform):
        return TypesInSignature(
            name=self.name,
            signature=self.signature,
            kind=self.kind,
            types=list(map(transform, self.types)),
        )

    def is_shallowly_equal(self, other):
        return isinstance(other, TypesInSignature) and len(self.types) == len(
            other.types
        )

    def destruct(self):
        return self.types


def find_common_typealias_abbreviation(variants):
    def get_classifiers(tree):
        if isinstance(tree, TypeWithExpansionNode):
            return tree.types

        if isinstance(tree, TypeNode):
            return [tree]

        if isinstance(tree, TypeProjectionNode):
            return get_classifiers(tree.type)

        return []

    if len(variants) == 0:
        return None

    classifiers = get_classifiers(variants[0])

    def exists_in_every_other_variant(abbreviation):
        for variant in variants[1:]:
            variant_classifiers = get_classifiers(variant)
            exists = False

            for abbreviation_variant in variant_classifiers:
                if str(abbreviation_variant) == str(abbreviation):
                    exists = True
                    break

            if not exists:
                return False

        return True

    for abbreviation in classifiers:
        if exists_in_every_other_variant(abbreviation):
            return abbreviation

    return None


def extract_inconsistencies_tree(platformToTree, origins=None):
    # common_abbreviation = find_common_typealias_abbreviation(list(platformToTree.values()))
    #
    # if common_abbreviation is not None:
    #     # print(f"Found common abbreviation: {common_abbreviation}")
    #     # exit(1)
    #     return []

    equivalenceClasses = []

    for platform, tree in platformToTree.items():
        if len(equivalenceClasses) == 0:
            equivalenceClasses.append({platform: tree})
            continue

        added_to_existing = False

        for existing in equivalenceClasses:
            representative = list(existing.items())[0]

            if unwrap_typealias(representative[1]).is_shallowly_equal(
                unwrap_typealias(tree)
            ):
                existing[platform] = tree
                added_to_existing = True
                break

        if not added_to_existing:
            equivalenceClasses.append({platform: tree})

    incompatible_configurations = []

    if len(equivalenceClasses) > 1:
        incompatible_configurations.append(platformToTree)

    for equivalenceClass in equivalenceClasses:
        delegate_inconsistencies = []
        delegate_inconsistencies_origins = []

        for platform, tree in equivalenceClass.items():
            for index, component in enumerate(tree.destruct()):
                if component is None:
                    print(f"Error > component is None for {index}: tree = {tree}")
                    exit(1)

                while index >= len(delegate_inconsistencies):
                    delegate_inconsistencies.append({})
                    delegate_inconsistencies_origins.append({})

                delegate_inconsistencies[index][platform] = component
                delegate_inconsistencies_origins[index][platform] = tree

        for delegate_index, delegate in enumerate(delegate_inconsistencies):
            other_incompatible = extract_inconsistencies_tree(
                delegate, origins=delegate_inconsistencies_origins[delegate_index]
            )
            incompatible_configurations += other_incompatible

    return incompatible_configurations


def parse_minimize(string):
    tree = parse_type_with_expansion(ParsingContext(string))

    if tree is None:
        print(f"Error > Parsing > {string}")
        exit()

    return tree


if __name__ == "__main__":
    tree = parse_minimize(
        "kotlin/Function1<platform/MediaPlayer/MPRemoteCommandEvent?, platform/MediaPlayer/MPRemoteCommandHandlerStatus -> platform/darwin/NSInteger -> kotlin/Long>"
    )

    print(tree)
    print(drop_typealiases(tree))

    sample = {
        "watchos_arm32": parse_minimize(
            "kotlin/Function1<platform/Foundation/NSURLSessionResponseDisposition -> platform/darwin/NSInteger -> kotlin/Int, kotlin/Unit>"
        ),
        "macos_x64": parse_minimize(
            "kotlin/Function1<platform/Foundation/NSURLSessionResponseDisposition -> platform/darwin/NSInteger -> kotlin/Long, kotlin/Unit>"
        ),
        "tvos_simulator_arm64": parse_minimize(
            "kotlin/Function1<platform/Foundation/NSURLSessionResponseDisposition -> platform/darwin/NSInteger -> kotlin/Long, kotlin/Unit>"
        ),
        "watchos_arm64": parse_minimize(
            "kotlin/Function1<platform/Foundation/NSURLSessionResponseDisposition -> platform/darwin/NSInteger -> kotlin/Int, kotlin/Unit>"
        ),
        "macos_arm64": parse_minimize("kotlin/Int"),
    }

    print(extract_inconsistencies_tree(sample))
