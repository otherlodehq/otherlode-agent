package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.ScalaClassDetector
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/**
 * The methods scalac emits from a declaration rather than from a body the adopter wrote, marked
 * from bytecode shape alone. Nothing here decodes `ScalaSig` or TASTy, and no line number is read:
 * each generated method is recognised by its body, which is fixed compiler output whatever the
 * source layout. Every shape below was read out of `javap -c -p` over the
 * `:fixtures-scala2` and `:fixtures-scala3` modules, compiled with Scala 2.13.15 and 3.3.4
 * (`Targets.scala` and `CaseShapes.scala`). A body that is not exactly one of these marks nothing,
 * so a release that writes a shape not read here leaves that method unmarked.
 *
 * Those two releases are the baseline. The shapes other releases write are read beside them, never
 * in place of them, each from `javap -c -p` over the same fixtures compiled by that release
 * (`fixtures-compilers/scalac`, which `ScalacMatrixTest` compares against the baseline marks):
 *
 * - Scala 2.12.20: `hashCode` without the `productPrefix` mix, `equals` in source order,
 *   `productElement` throwing through `Integer.toString`, and a private `readResolve` on every
 *   serializable module class ([ClassShape.readResolveMatches]). These are read only in a class
 *   2.12 may have written ([CaseClass.mayBeScala212]), and the `readResolve` only in a Scala 2
 *   module class with no `writeReplace`.
 * - Scala 2.13.17 and later, Scala 3.3.7 and later on the 3.3 line and 3.7.1 and later: `hashCode`
 *   with the prefix hash folded into a constant ([matchesHashCode]).
 * - Scala 3.3.8, 3.8.4 and later: `equals` through `Objects.equals` ([compareElements]).
 * - Scala 3.7.0 and later: `fromProduct` reading the elements into locals first ([matchesFromProduct]).
 * - Scala 3.7.3 to 3.8.3: `equals` through a copy of the cast instance ([matchesEqualsScala3]).
 * - Scala 3.9.0: `productElement` and `productElementName` throwing through
 *   `IndexOutOfBoundsException.<init>(int)` ([matchesIndexed]).
 *
 * A Scala 3 `enum`'s plumbing ([enumPlumbing]) was read from every release from 3.3.3 to 3.9.0.
 * Its bodies are those of 3.3.4 with three exceptions: 3.3.3 words `valueOf`'s and `fromOrdinal`'s
 * failures differently and, with 3.4.0 and 3.4.1, passes the companion to a singleton case's
 * constructor in `$new`; 3.8.4 and later leave `scala.Product` out of the enum class's interfaces,
 * which the rules do not read; and the singleton case's `hashCode`, `String.hashCode` of the case
 * name, is written from 3.3.7 on the 3.3 line and from 3.7.3.
 */
internal object ScalaGeneratedMethods {
    private const val MODULE_FIELD = "MODULE\$"

    /** Scala 3.3.3's `valueOf` message recipe, which names no enum. */
    private const val OLD_NO_CASE_RECIPE = "enum case not found: \u0001"
    private const val OUTER_FIELD = "\$outer"
    private const val OBJECT = "java/lang/Object"
    private const val STRING = "java/lang/String"
    private const val PRODUCT = "scala/Product"
    private const val ITERATOR = "Lscala/collection/Iterator;"
    private const val STATICS = "scala/runtime/Statics"
    private const val BOXES = "scala/runtime/BoxesRunTime"
    private const val RUNTIME = "scala/runtime/ScalaRunTime\$"
    private const val OUT_OF_BOUNDS = "java/lang/IndexOutOfBoundsException"
    private const val SERIALIZATION_PROXY = "scala/runtime/ModuleSerializationProxy"
    private const val MURMUR = "scala/util/hashing/MurmurHash3\$"
    private const val OBJECTS = "java/util/Objects"
    private const val INTEGER = "java/lang/Integer"
    private const val ENUM = "scala/reflect/Enum"
    private const val ENUM_VALUE = "scala/runtime/EnumValue"
    private const val MIRROR_SUM = "scala/deriving/Mirror\$Sum"
    private const val MIRROR_SINGLETON = "scala/deriving/Mirror\$Singleton"
    private const val NO_SUCH_ELEMENT = "java/util/NoSuchElementException"
    private const val ILLEGAL_ARGUMENT = "java/lang/IllegalArgumentException"
    private const val STRING_CONCAT = "java/lang/invoke/StringConcatFactory"
    private const val THROWABLE = "java/lang/Throwable"
    private const val VALUES_FIELD = "\$values"
    private const val CONCAT_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;"

    /** The flags scalac gives the static field of each enum case in the enum's companion, `ACC_ENUM` aside. */
    private const val PLAIN_CASE_FIELD_FLAGS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    /** The seed of scalac's `hashCode` fold, `0xcafebabe`. */
    private const val HASH_SEED = -889275714

    /** Scala 3's `_1`, `_2` and on, which a case class gets for each element. */
    private val ELEMENT_ALIAS = Regex("_([1-9]\\d*)")

    /** Scala 2's accessor for a `private` or `protected` element, `a$access$0` for element 0 named `a`. */
    private val ACCESS_ACCESSOR = Regex("(.+)\\\$access\\\$(\\d+)")

    /**
     * The four `scala.Product` members every case class declares, a case object's module class
     * included, in both Scala versions: `javap` over `Cc`, `Multi`, `Round`, `Empty` and `Solo$`.
     */
    private val CASE_CLASS_SIGNATURE =
        setOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productArity" to "()I",
            "productElement" to "(I)Ljava/lang/Object;",
            "productPrefix" to "()Ljava/lang/String;",
        )

    /** The names [companionPlumbing] can mark; a class declaring none of them has no partner to read. */
    private val COMPANION_METHODS = setOf("apply", "unapply", "toString", "fromProduct")

    /** The only element types scalac 2.13.15 specialises `Tuple2` on, by descriptor. */
    private val TUPLE2_SPECIALISED = setOf("I", "J", "D", "C", "Z")

    private val WRITE_REPLACE = "writeReplace" to "()Ljava/lang/Object;"
    private val READ_RESOLVE = "readResolve" to "()Ljava/lang/Object;"
    private val PRODUCT_ELEMENT_NAME = "productElementName" to "(I)Ljava/lang/String;"

    /**
     * The generated methods of the Scala class [classBytes], keyed by name and descriptor. The
     * caller has already found a `Scala` or `ScalaSig` attribute on the class. [lookup] reads other
     * classes as bytes, never loading them: a companion's partner, and for an enum its class,
     * companion, singleton-case class and parameterised cases. A lookup that returns null or throws marks
     * nothing on the partner's account.
     *
     * Each method gets at most one mark, tried in this order: [GeneratedBy.STATIC_FORWARDER] (see
     * [ClassShape.staticForwarders]), then [GeneratedBy.ENUM] on a Scala 3 enum's plumbing (see
     * [enumPlumbing]), then [GeneratedBy.CASE_CLASS] on a case class's own plumbing
     * (see [caseClassPlumbing]), then [GeneratedBy.CASE_CLASS] on a companion's (see
     * [companionPlumbing]), then [GeneratedBy.SCALA_OBJECT] (see [ClassShape.writeReplaceMatches]
     * and [ClassShape.readResolveMatches]).
     *
     * A class whose name ends in `$` reads its partner only when it declares an instance method
     * named `apply`, `unapply`, `toString` or `fromProduct`: without one, the companion rule has
     * nothing to mark, whatever the partner turns out to be.
     */
    fun of(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): Map<Pair<String, String>, GeneratedBy> = marksOf(readShape(classBytes), lookup)

    /**
     * What [analyse] found in one Scala class: its generated methods ([generated], what [of]
     * returns), and the methods that sit in an outline of compiler output but match none of the
     * shapes read ([unread], by family). [unreadRelease] is the Scala 3 release that compiled the
     * class when [unread] holds methods because that release is not on the read list, and null when
     * the class names no compiler (Scala 2, or Scala 3 with no readable `.tasty`) or the unread
     * methods are none. A method is in one map at most.
     */
    class Result(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val unread: Map<Pair<String, String>, UnreadShape>,
        val unreadRelease: String?,
        /** Why [unread] holds methods; null when it holds none. */
        val cause: UnreadCause? = null,
    )

    /**
     * [of] plus the unread shapes of the class. An outline method the rules leave unmarked is an
     * unread shape unless the class names its compiler and the agent has read that compiler, in
     * which case the method is hand-written and stays ordinary (ADR 0054). A method scalac refuses to
     * let the adopter write ([cannotBeHandWritten]) stays an unread shape on a read release too:
     * there it is scalac's in a shape the rules do not read, such as an enum declared inside a class
     * or one with backticked case names. [releaseOf] is asked, with the class's internal name, only when such a method
     * exists and the class is Scala 3's.
     */
    fun analyse(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
        releaseOf: (internalName: String) -> String?,
    ): Result {
        val shape = readShape(classBytes)
        val generated = marksOf(shape, lookup)
        val candidates = outlineOf(shape, lookup, generated)
        if (candidates.isEmpty()) return Result(generated, emptyMap(), null)
        if (!shape.isScala3) return Result(generated, candidates, null, UnreadCause.VERSION_BLIND)
        val release = releaseOf(shape.internalName)
        return when {
            release == null -> {
                Result(generated, candidates, null, UnreadCause.VERSION_BLIND)
            }

            ScalaReleases.isRead(release) -> {
                val held = candidates.filterKeys { shape.cannotBeHandWritten(it, lookup) }
                Result(generated, held, null, UnreadCause.UNREAD_STRUCTURE.takeIf { held.isNotEmpty() })
            }

            else -> {
                Result(generated, candidates, release, UnreadCause.UNREAD_RELEASE)
            }
        }
    }

    /**
     * Whether scalac refuses a hand-written method [key] in this class, so a method of that name and
     * descriptor can only be its own: the `values`, `valueOf`, `fromOrdinal`, `$new` and
     * `ordinal(Object)` of an enum's companion (a hand-written one is a double definition), and any
     * method of the class of an enum case, a class implementing `scala.runtime.EnumValue` whose
     * superclass is a Scala enum, since a case has no body. A companion's `ordinal(E)` and
     * `writeReplace` can be written by hand and are not held.
     */
    private fun ClassShape.cannotBeHandWritten(
        key: Pair<String, String>,
        lookup: (String) -> ByteArray?,
    ): Boolean {
        if (ENUM_VALUE in interfaces) return superName?.let { readClass(it, lookup) }?.let { ENUM in it.interfaces } == true
        if (!isEnumCompanionShape(lookup)) return false
        val (name, descriptor) = key
        return name in REFUSED_COMPANION_METHODS || (name == "ordinal" && descriptor == "(L$OBJECT;)I")
    }

    /**
     * A class named for an enum's companion that implements `scala.deriving.Mirror$Sum` and whose
     * partner reads as an enum class. An enum declared inside a class has a companion with no
     * `MODULE$`, so this does not require a module class.
     */
    private fun ClassShape.isEnumCompanionShape(lookup: (String) -> ByteArray?): Boolean =
        internalName.endsWith("$") && MIRROR_SUM in interfaces && readClass(partnerName(this), lookup)?.isEnumClass() == true

    private val REFUSED_COMPANION_METHODS = setOf("values", "valueOf", "fromOrdinal", "\$new")

    /** A Scala 2 class marked serializable, as scalac writes a `writeReplace` or `readResolve` only for one. */
    private val ClassShape.isSerializable: Boolean
        get() = "java/io/Serializable" in interfaces || "scala/Serializable" in interfaces

    private fun marksOf(
        shape: ClassShape,
        lookup: (internalName: String) -> ByteArray?,
    ): Map<Pair<String, String>, GeneratedBy> {
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()
        for (key in shape.staticForwarders()) result.putIfAbsent(key, GeneratedBy.STATIC_FORWARDER)
        for (key in enumPlumbing(shape, lookup)) result.putIfAbsent(key, GeneratedBy.ENUM)
        caseClassOf(shape)?.let { case -> for (key in caseClassPlumbing(case)) result.putIfAbsent(key, GeneratedBy.CASE_CLASS) }
        if (shape.internalName.endsWith("$") && declaresCompanionCandidate(shape)) {
            val partner =
                partnerName(shape).let { name ->
                    try {
                        lookup(name)?.let(::readShape)
                    } catch (_: Exception) {
                        null
                    }
                }
            partner?.let(::caseClassOf)?.takeIf { !it.isObject }?.let { case ->
                for (key in companionPlumbing(shape, case)) result.putIfAbsent(key, GeneratedBy.CASE_CLASS)
            }
        }
        if (shape.isModuleClass && shape.writeReplaceMatches()) result.putIfAbsent(WRITE_REPLACE, GeneratedBy.SCALA_OBJECT)
        // Scala 2.12 writes the readResolve and no writeReplace; 2.13 the reverse, and Scala 3 neither.
        // A hand-written writeReplace beside 2.12's readResolve does not make it the adopter's.
        if (!shape.isScala3 && shape.isModuleClass && !shape.writeReplaceMatches() && shape.readResolveMatches()) {
            result.putIfAbsent(READ_RESOLVE, GeneratedBy.SCALA_OBJECT)
        }
        return result
    }

    /**
     * The outline of compiler output in [shape]: each method scalac writes for a plumbing family,
     * named by its name and the descriptor derived from the class itself, whatever its body is,
     * for the methods [generated] left unmarked. The outlines follow the order the marks are tried
     * in, so a method is in the first family that names it:
     *
     * - [UnreadShape.STATIC_FORWARDER]: a static, non-synthetic, non-bridge method whose `$` twin
     *   declares an instance method of the same name and descriptor.
     * - [UnreadShape.SCALA_ENUM]: a Scala 3 enum's plumbing ([enumOutline]).
     * - [UnreadShape.CASE_CLASS]: a case class's own plumbing ([caseClassOutline]) and its
     *   companion's ([companionOutline]).
     * - [UnreadShape.SCALA_OBJECT]: a module class's `writeReplace()` and, in Scala 2, `readResolve()`
     *   when the class declares no `writeReplace`; in Scala 2, only for a serializable module class.
     *
     * A method outside every outline, such as a field accessor or a hand-written overload with
     * another descriptor, is the adopter's code.
     */
    private fun outlineOf(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
        generated: Map<Pair<String, String>, GeneratedBy>,
    ): Map<Pair<String, String>, UnreadShape> {
        val open = shape.methods.filterKeys { it !in generated }
        if (open.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Pair<String, String>, UnreadShape>()

        fun add(
            keys: Collection<Pair<String, String>>,
            family: UnreadShape,
            static: Boolean = false,
        ) {
            for (key in keys) if (open[key]?.isStatic == static) result.putIfAbsent(key, family)
        }
        add(staticForwarderOutline(shape, open, lookup), UnreadShape.STATIC_FORWARDER, static = true)
        add(enumOutline(shape, lookup), UnreadShape.SCALA_ENUM)
        caseClassOf(shape)?.let { add(caseClassOutline(it), UnreadShape.CASE_CLASS) }
        if (shape.internalName.endsWith("$") && open.any { (key, method) -> !method.isStatic && key.first in COMPANION_METHODS }) {
            readClass(partnerName(shape), lookup)?.let(::caseClassOf)?.takeIf { !it.isObject }?.let { partner ->
                add(companionOutline(shape, partner), UnreadShape.CASE_CLASS)
            }
        }
        // Scala 3 gives every object a writeReplace. Scala 2 writes one (2.13) or a readResolve
        // (2.12) only for a serializable object, so a readResolve beside scalac's own writeReplace
        // is the adopter's, while one beside a hand-written writeReplace may be 2.12's.
        if (shape.isModuleClass && (shape.isScala3 || shape.isSerializable)) {
            add(listOf(WRITE_REPLACE), UnreadShape.SCALA_OBJECT)
            if (!shape.isScala3 && !shape.writeReplaceMatches()) add(listOf(READ_RESOLVE), UnreadShape.SCALA_OBJECT)
        }
        return result
    }

    private fun staticForwarderOutline(
        shape: ClassShape,
        open: Map<Pair<String, String>, MethodShape>,
        lookup: (String) -> ByteArray?,
    ): List<Pair<String, String>> {
        val excluded = Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
        val statics = open.filter { (key, method) -> method.isStatic && method.access and excluded == 0 && key.first != "<clinit>" }
        if (statics.isEmpty()) return emptyList()
        val twin = readClass("${shape.internalName}$", lookup)?.takeIf { it.isModuleClass } ?: return emptyList()
        return statics.keys.filter { twin.methods[it]?.isStatic == false }
    }

    /** The descriptors a `copy` or an `apply` returning [returnType] has: each primary constructor's parameters, an outer reference left out. */
    private fun constructionDescriptors(
        shape: ClassShape,
        returnType: String,
    ): List<String> =
        shape.methods.keys
            .filter { (name, descriptor) -> name == "<init>" && shape.isPrimaryConstructor(descriptor) }
            .map { (_, descriptor) ->
                val parameters = Type.getArgumentTypes(descriptor).toList()
                val outer = if (parameters.firstOrNull()?.descriptor == shape.outerFieldDescriptor) 1 else 0
                "(${parameters.drop(outer).joinToString("") { it.descriptor }})$returnType"
            }

    /**
     * Mirrors [enumPlumbing]: the methods of an enum class, its companion, a singleton case and a
     * parameterised case that the enum rules read, with the descriptors they read them with.
     */
    private fun enumOutline(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): List<Pair<String, String>> {
        if (!shape.isScala || !shape.isScala3) return emptyList()
        val string = "L$STRING;"
        val objectType = "L$OBJECT;"
        if (shape.isEnumClass()) {
            return listOf(
                "productIterator" to "()$ITERATOR",
                "productPrefix" to "()$string",
                "productElementNames" to "()$ITERATOR",
                "productElementName" to "(I)$string",
            )
        }
        if (shape.internalName.endsWith("$") && MIRROR_SUM in shape.interfaces) {
            val enumClass = readClass(shape.internalName.removeSuffix("$"), lookup)?.takeIf { it.isEnumClass() } ?: return emptyList()
            val enumType = "L${enumClass.internalName};"
            return listOf(
                "values" to "()[$enumType",
                "valueOf" to "($string)$enumType",
                "fromOrdinal" to "(I)$enumType",
                "\$new" to "(I$string)$enumType",
                "ordinal" to "($enumType)I",
                "ordinal" to "($objectType)I",
            )
        }
        val singleton = ENUM_VALUE in shape.interfaces && MIRROR_SINGLETON in shape.interfaces && shape.isFinal
        if (!singleton && !shape.declaresConstantOrdinal()) return emptyList()
        val enumClass = shape.superName?.let { readClass(it, lookup) }?.takeIf { it.isEnumClass() } ?: return emptyList()
        val family = enumFamilyOf(enumClass, lookup) ?: return emptyList()
        if (!singleton) {
            val fits = shape.isFinal && ENUM_VALUE !in shape.interfaces && family.ordinalOf(shape.sourceName) >= 0
            return if (fits) listOf("ordinal" to "()I") else emptyList()
        }
        if (!shape.isEnumSingletonOf(enumClass)) return emptyList()
        val product = "L$PRODUCT;"
        return listOf(
            "canEqual" to "($objectType)Z",
            "productArity" to "()I",
            "productElement" to "(I)$objectType",
            "productElementName" to "(I)$string",
            "fromProduct" to "($product)L$MIRROR_SINGLETON;",
            "fromProduct" to "($product)$objectType",
            READ_RESOLVE,
            "ordinal" to "()I",
            "productPrefix" to "()$string",
            "toString" to "()$string",
            "hashCode" to "()I",
        )
    }

    /**
     * The members scalac writes for a case class, whatever their bodies: the Product and
     * `equals`, `hashCode` and `toString` members, `copy` (not on a case object) with the primary
     * constructor's parameters, and an accessor per element, Scala 3's `_N()` or Scala 2's
     * `<field>$access$N()`. When the elements cannot be worked out, the accessors are the methods
     * named in either spelling with no parameters.
     */
    private fun caseClassOutline(case: CaseClass): List<Pair<String, String>> {
        val string = "L$STRING;"
        val keys =
            mutableListOf(
                "canEqual" to "(L$OBJECT;)Z",
                "productArity" to "()I",
                "productElement" to "(I)L$OBJECT;",
                "productElementName" to "(I)$string",
                "productElementNames" to "()$ITERATOR",
                "productIterator" to "()$ITERATOR",
                "productPrefix" to "()$string",
                "hashCode" to "()I",
                "toString" to "()$string",
            )
        if (!case.isObject) {
            keys += "equals" to "(L$OBJECT;)Z"
            for (descriptor in constructionDescriptors(case.shape, "L${case.name};")) keys += "copy" to descriptor
        }
        val elements = case.elements
        if (elements != null) {
            for (element in elements) {
                val descriptor = "()${element.type}"
                keys +=
                    if (case.shape.isScala3) {
                        "_${element.index + 1}" to descriptor
                    } else {
                        "${element.name}\$access\$${element.index}" to
                            descriptor
                    }
            }
        } else {
            val pattern = if (case.shape.isScala3) ELEMENT_ALIAS else ACCESS_ACCESSOR
            keys +=
                case.shape.methods.keys
                    .filter { (name, descriptor) -> pattern.matches(name) && descriptor.startsWith("()") }
        }
        return keys
    }

    /** The members scalac writes in the companion of the case class [partner], whatever their bodies. */
    private fun companionOutline(
        companion: ClassShape,
        partner: CaseClass,
    ): List<Pair<String, String>> {
        val partnerType = "L${partner.name};"
        val scala3 = companion.isScala3
        val keys = mutableListOf("toString" to "()L$STRING;")
        for (descriptor in constructionDescriptors(partner.shape, partnerType)) keys += "apply" to descriptor
        val empty = partner.elements?.isEmpty()
        val unapply =
            when {
                empty == true -> listOf("Z")
                empty == false -> listOf(if (scala3) partnerType else "Lscala/Option;")
                scala3 -> listOf("Z", partnerType)
                else -> listOf("Z", "Lscala/Option;")
            }
        for (result in unapply) keys += "unapply" to "($partnerType)$result"
        if (scala3) {
            keys += "fromProduct" to "(L$PRODUCT;)$partnerType"
            keys += "fromProduct" to "(L$PRODUCT;)L$OBJECT;"
        }
        return keys
    }

    private fun declaresCompanionCandidate(shape: ClassShape): Boolean =
        shape.methods.any { (key, method) -> !method.isStatic && key.first in COMPANION_METHODS }

    /**
     * The class a companion [shape] pairs with. For an inner or local companion it is the one
     * class other than itself that the companion's own `InnerClasses` attribute lists beside the
     * same outer class, under the same [sourceName] and with no trailing `$`: scalac names a local
     * case class and its companion `Outer$Local$1` and `Outer$Local$2$` in Scala 2.13.15, simple
     * names `Local$1` and `Local$2$`, and `Outer$Local$1` and `Outer$Local$3$` in Scala 3.3.4,
     * simple names `Local` and `Local$`. Otherwise it is the class named like [shape] without the
     * trailing `$`, as for `Cc$` and `Cc`, or `Outer$Inner$` and `Outer$Inner`.
     */
    private fun partnerName(shape: ClassShape): String {
        val self = shape.innerClasses.firstOrNull { it.name == shape.internalName }
        if (self != null) {
            val sourceName = shape.sourceName
            shape.innerClasses
                .filter { entry ->
                    entry.name != shape.internalName &&
                        entry.outerName == self.outerName &&
                        entry.innerName?.endsWith("$") == false &&
                        sourceName(entry.name, entry) == sourceName
                }.singleOrNull()
                ?.let { return it.name }
        }
        return shape.internalName.removeSuffix("$")
    }

    /**
     * The source name scalac gives the class [internalName] in `productPrefix` and a companion's
     * `toString`: the simple name from its `InnerClasses` [entry], or the name after the package
     * for a top-level class, without the trailing `$` of an object's class. A local class, one whose
     * entry names no outer class, also loses the `$<n>` Scala 2.13.15 appends to its simple name
     * (`Local$1` and `Local$2$`, both `Local`).
     */
    private fun sourceName(
        internalName: String,
        entry: InnerClassEntry?,
    ): String {
        val simple = entry?.innerName ?: internalName.substringAfterLast('/')
        val name = if (internalName.endsWith("$")) simple.removeSuffix("$") else simple
        return if (entry != null && entry.outerName == null) name.replace(LOCAL_SUFFIX, "") else name
    }

    private val LOCAL_SUFFIX = Regex("\\$\\d+$")

    /** One declared field: its access flags, name and descriptor. */
    private class FieldShape(
        val access: Int,
        val name: String,
        val descriptor: String,
    )

    /** One exception-table entry, its range and target as instruction indices. */
    private data class Handler(
        val start: Int,
        val end: Int,
        val target: Int,
        val type: String?,
    )

    /** One entry of a class's `InnerClasses` attribute. */
    private class InnerClassEntry(
        val name: String,
        val outerName: String?,
        val innerName: String?,
    )

    /**
     * One declared method: its access flags and its instructions, [code], null for a method with
     * none. [body] is the same list, and null for a method with a try-catch block too: no shape
     * here has a handler, so only a constructor's opening stores are ever read past one.
     */
    private class MethodShape(
        val access: Int,
    ) {
        var code: List<Insn>? = null
        var hasHandler = false
        var handlers: List<Handler> = emptyList()

        val body: List<Insn>? get() = code?.takeIf { !hasHandler }

        /**
         * Whether the method invokes a constructor of its own class on itself: it has more
         * `invokespecial <own class>.<init>` calls than `new <own class>` instructions. Every
         * auxiliary constructor does, while a primary constructor that builds another instance of
         * its own class (`Node`) pairs each such call with a `new`.
         */
        var delegatesToOwnConstructor = false

        val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
        val isPrivate: Boolean get() = access and Opcodes.ACC_PRIVATE != 0
    }

    /** What the marking rules ask of one class, read in one pass over its bytes. */
    private class ClassShape(
        val internalName: String,
        val isScala: Boolean,
        val isScala3: Boolean,
        val isFinal: Boolean,
        val hasOwnModuleField: Boolean,
        val outerFieldDescriptor: String?,
        val methods: Map<Pair<String, String>, MethodShape>,
        val innerClasses: List<InnerClassEntry>,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
        val fields: List<FieldShape>,
    ) {
        /** A class compiled for a Scala `object`: its name ends in `$` and it holds its own instance in `MODULE$`. */
        val isModuleClass: Boolean get() = internalName.endsWith("$") && hasOwnModuleField

        /**
         * Whether [descriptor] names this class's primary constructor: a constructor that does not
         * delegate to another of its own class, as an auxiliary constructor always does (see
         * [MethodShape.delegatesToOwnConstructor]).
         */
        fun isPrimaryConstructor(descriptor: String): Boolean = methods["<init>" to descriptor]?.delegatesToOwnConstructor == false

        /** This class's own source name; see [ScalaGeneratedMethods.sourceName]. */
        val sourceName: String get() = sourceName(internalName, innerClasses.firstOrNull { it.name == internalName })

        fun instanceMethods(): List<Pair<Pair<String, String>, List<Insn>>> =
            methods.mapNotNull { (key, method) -> method.body?.takeIf { !method.isStatic }?.let { key to it } }

        /**
         * The static forwarders: a method that is static, not synthetic, not a bridge and has a body,
         * whose instructions are exactly `getstatic <ThisClass>$.MODULE$`, each parameter loaded in
         * declaration order with the load opcode for its type, `invokevirtual <ThisClass>$.<its own
         * name and descriptor>`, and the return opcode for its return type. Both Scala versions emit
         * forwarders with flags `ACC_PUBLIC, ACC_STATIC` and that body, on an object's own class and
         * on a case class for its companion's methods.
         */
        fun staticForwarders(): Set<Pair<String, String>> {
            val excluded = Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
            val moduleClass = "$internalName$"
            return methods.entries
                .filter { (key, method) -> method.isStatic && method.access and excluded == 0 && key.first != "<clinit>" }
                .filter { (key, method) ->
                    val (name, descriptor) = key
                    val expected =
                        buildList {
                            add(Insn.Field(Opcodes.GETSTATIC, moduleClass, MODULE_FIELD, "L$moduleClass;"))
                            addAll(parameterLoads(descriptor, firstSlot = 0))
                            add(Insn.Call(Opcodes.INVOKEVIRTUAL, moduleClass, name, descriptor))
                            add(Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)))
                        }
                    method.body == expected
                }.mapTo(mutableSetOf()) { it.key }
        }

        /**
         * Whether the class declares a private `writeReplace()Ljava/lang/Object;` whose instructions
         * are exactly `new scala/runtime/ModuleSerializationProxy`, `dup`, `ldc` of the class itself,
         * `invokespecial ModuleSerializationProxy.<init>(Ljava/lang/Class;)V` and `areturn`. Scala 3
         * gives every object that method and Scala 2 every case-class companion.
         */
        fun writeReplaceMatches(): Boolean {
            val method = methods[WRITE_REPLACE] ?: return false
            return method.isPrivate &&
                method.body ==
                listOf(
                    Insn.TypeOperand(Opcodes.NEW, SERIALIZATION_PROXY),
                    Insn.Plain(Opcodes.DUP),
                    Insn.Constant(Type.getObjectType(internalName)),
                    Insn.Call(Opcodes.INVOKESPECIAL, SERIALIZATION_PROXY, "<init>", "(Ljava/lang/Class;)V"),
                    Insn.Plain(Opcodes.ARETURN),
                )
        }

        /**
         * Whether the class declares a private `readResolve()Ljava/lang/Object;` whose instructions
         * are exactly `getstatic <Class>.MODULE$` and `areturn`. Scala 2.12.20 gives every
         * serializable module class that method, and 2.13.15 and later and Scala 3 give none.
         */
        fun readResolveMatches(): Boolean {
            val method = methods[READ_RESOLVE] ?: return false
            return method.isPrivate &&
                method.body ==
                listOf(Insn.Field(Opcodes.GETSTATIC, internalName, MODULE_FIELD, "L$internalName;"), Insn.Plain(Opcodes.ARETURN))
        }
    }

    /**
     * Reads [classBytes] once for [ClassShape]. A class is Scala 3's when it carries a `TASTY`
     * attribute, or when it carries neither `ScalaSig` nor `ScalaInlineInfo`. Over the fixture
     * modules, Scala 3.3.4 writes `Scala` on every class and `TASTY` beside it on a top-level
     * one, and never `ScalaSig` or `ScalaInlineInfo`; Scala 2.13.15 writes `ScalaSig` on a
     * top-level class, `Scala` on a module, inner or local class, and `ScalaInlineInfo` on all of
     * them but an object's mirror class, and never `TASTY`. The top-level attributes alone would
     * leave an inner or local case class, which carries only `Scala` in Scala 3.3.4, undecided.
     */
    private fun readShape(classBytes: ByteArray): ClassShape {
        var internalName = ""
        var isScala = false
        var isFinal = false
        var hasTasty = false
        var hasScala2Attribute = false
        var hasOwnModuleField = false
        var outerFieldDescriptor: String? = null
        var classAccess = 0
        var classSuper: String? = null
        var classInterfaces = emptyList<String>()
        val fields = mutableListOf<FieldShape>()
        val methods = mutableMapOf<Pair<String, String>, MethodShape>()
        val innerClasses = mutableListOf<InnerClassEntry>()

        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaceNames: Array<out String>?,
                ) {
                    internalName = name
                    isFinal = access and Opcodes.ACC_FINAL != 0
                    classAccess = access
                    classSuper = superName
                    classInterfaces = interfaceNames.orEmpty().toList()
                }

                override fun visitAttribute(attribute: Attribute) {
                    if (ScalaClassDetector.isScalaAttribute(attribute)) isScala = true
                    when (attribute.type) {
                        "TASTY" -> hasTasty = true
                        "ScalaSig", "ScalaInlineInfo" -> hasScala2Attribute = true
                    }
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    innerClasses += InnerClassEntry(name, outerName, innerName)
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (access and Opcodes.ACC_STATIC != 0 && name == MODULE_FIELD && descriptor == "L$internalName;") {
                        hasOwnModuleField = true
                    }
                    if (access and Opcodes.ACC_STATIC == 0 && name == OUTER_FIELD) outerFieldDescriptor = descriptor
                    fields += FieldShape(access, name, descriptor)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val method = MethodShape(access)
                    methods[name to descriptor] = method
                    val owner = internalName
                    return BodyRecorder { code, hasHandler, handlers ->
                        method.code = code
                        method.hasHandler = hasHandler
                        method.handlers = handlers
                        val constructions = code.orEmpty().count { it == Insn.TypeOperand(Opcodes.NEW, owner) }
                        val ownConstructorCalls =
                            code.orEmpty().count {
                                it is Insn.Call && it.opcode == Opcodes.INVOKESPECIAL && it.owner == owner &&
                                    it.name == "<init>"
                            }
                        method.delegatesToOwnConstructor = ownConstructorCalls > constructions
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val isScala3 = hasTasty || !hasScala2Attribute
        return ClassShape(
            internalName,
            isScala,
            isScala3,
            isFinal,
            hasOwnModuleField,
            outerFieldDescriptor,
            methods,
            innerClasses,
            classAccess,
            classSuper,
            classInterfaces,
            fields,
        )
    }

    /**
     * One element of a case class: a parameter of its first parameter list, which scalac stores in
     * a field of the same name and reads back through [accessor]. Scala 2.13.15 reads a `private`
     * or `protected` element through a public `<name>$access$<index>`, and every other element, in
     * both versions, through the accessor named like the field; a private accessor is called with
     * `invokespecial` (`javap` over `Priv` and `Hidden`).
     */
    private class Element(
        val index: Int,
        val name: String,
        val type: Type,
        val accessor: String,
        private val accessorPrivate: Boolean,
        private val owner: String,
    ) {
        /** The call that reads this element from an instance already on the stack, from inside [owner]. */
        val read: Insn get() = Insn.Call(if (accessorPrivate) Opcodes.INVOKESPECIAL else Opcodes.INVOKEVIRTUAL, owner, accessor, "()$type")

        /** The same read from another class, which only a non-private accessor allows. */
        val readFromOutside: Insn? get() = if (accessorPrivate) null else read

        val isPrimitive: Boolean get() = type.sort in Type.BOOLEAN..Type.DOUBLE
    }

    /**
     * A case class, or a case object's module class: a class carrying a Scala attribute that
     * declares `canEqual(Ljava/lang/Object;)Z`, `productArity()I`,
     * `productElement(I)Ljava/lang/Object;` and `productPrefix()Ljava/lang/String;`. The interface
     * list is not consulted: Scala 2.13.15 leaves `scala.Product` out of it when a supertype already
     * brings `Product`, as for `case class Round(r: Double) extends Figure`.
     *
     * [arity] is the constant `productArity` returns, and [elements] the fields the class's
     * constructor stores its first [arity] parameters in, each with an accessor. scalac's primary
     * constructor opens with one `aload_0`, parameter load and `putfield` per stored parameter, in
     * parameter order, the first parameter list first; a second list's parameter is stored only if
     * the body uses it, and a body `val` is assigned after the super call (`javap` over `TwoLists`,
     * `Svc`, `BodyVal` and `Outer$Inner`, whose outer reference is stored after its element).
     * Element `i` must be stored from parameter `i`, counted after the outer reference of an inner
     * or local class: Scala 2.13.15 passes an element that overrides a supertype's `val` to the
     * supertype's constructor and stores none of it (`Q`), and a constructor that skips a
     * parameter gives no elements rather than a shifted list.
     * Declaration order is no substitute: Scala 2.13.15 declares a body `lazy val`'s field before
     * the elements' fields. An auxiliary constructor opens with its call to the primary one and
     * contributes nothing. The stores are read even from a constructor with a try-catch block,
     * which a `try` in the class body puts there (`TryBody`), since they come before any handler.
     * Scala 2.13.15 reads a `private` or `protected` element through `<name>$access$<index>`, a name
     * Scala 3.3.4 never gives an element's accessor, so a Scala 3 class always reads its elements
     * through the accessor named like the field. [arity] and [elements] are each null when the
     * class does not have that shape, and then no method whose body depends on the elements is
     * marked.
     */
    private class CaseClass(
        val shape: ClassShape,
    ) {
        val name: String get() = shape.internalName
        val isObject: Boolean get() = shape.isModuleClass

        /**
         * Whether Scala 2.12 may have written this class: a Scala 2 class with no
         * `productElementName`, which 2.13 writes for every case class and case object and 2.12 for
         * none. The shapes only 2.12 writes are read only in such a class, so a 2.13 method written
         * by hand in 2.12's shape stays the adopter's.
         */
        val mayBeScala212: Boolean get() = !shape.isScala3 && PRODUCT_ELEMENT_NAME !in shape.methods

        val arity: Int? =
            (shape.methods["productArity" to "()I"]?.body)?.let { body ->
                val constant = body.firstOrNull() as? Insn.IntConstant
                if (constant != null && body == listOf(constant, Insn.Plain(Opcodes.IRETURN))) constant.value else null
            }

        val elements: List<Element>? =
            arity?.let { count ->
                val stored =
                    shape.methods
                        .filterKeys { it.first == "<init>" }
                        .entries
                        .mapNotNull { (key, method) ->
                            method.code
                                ?.let { storedParameters(key.second, it) }
                                ?.takeIf { fields -> fields.size >= count }
                                ?.take(count)
                        }.distinct()
                        .singleOrNull() ?: return@let null
                stored.mapIndexed { index, (fieldName, fieldDescriptor) ->
                    val descriptor = "()$fieldDescriptor"
                    val access = "$fieldName\$access\$$index"
                    val accessor =
                        when {
                            !shape.isScala3 && shape.methods.containsKey(access to descriptor) -> access
                            shape.methods.containsKey(fieldName to descriptor) -> fieldName
                            else -> return@let null
                        }
                    val private = shape.methods.getValue(accessor to descriptor).isPrivate
                    Element(index, fieldName, Type.getType(fieldDescriptor), accessor, private, name)
                }
            }

        /**
         * The fields, by name and descriptor, that the constructor [descriptor] with [body] stores
         * its parameters in before anything else: the run of `aload_0`, a load of the next
         * parameter's slot and a `putfield` of that parameter's type on this class with which it
         * opens, the first stored parameter being the first one after an outer reference. A
         * constructor whose first parameter has the type of the class's `$outer` field takes an
         * outer reference there.
         */
        private fun storedParameters(
            descriptor: String,
            body: List<Insn>,
        ): List<Pair<String, String>> {
            val parameters = Type.getArgumentTypes(descriptor)
            val first = if (parameters.firstOrNull()?.descriptor == shape.outerFieldDescriptor) 1 else 0
            var slot = 1 + parameters.take(first).sumOf { it.size }
            val stored = mutableListOf<Pair<String, String>>()
            for ((index, start) in (body.indices step 3).withIndex()) {
                val parameter = parameters.getOrNull(first + index) ?: break
                val store = body.getOrNull(start + 2) as? Insn.Field
                if (body[start] != aload(0) ||
                    body.getOrNull(start + 1) != Insn.Var(parameter.getOpcode(Opcodes.ILOAD), slot) ||
                    store == null ||
                    store.opcode != Opcodes.PUTFIELD ||
                    store.owner != name ||
                    store.descriptor != parameter.descriptor
                ) {
                    break
                }
                stored += store.name to store.descriptor
                slot += parameter.size
            }
            return stored
        }

        fun declares(
            name: String,
            descriptor: String,
        ): Boolean = shape.methods.containsKey(name to descriptor)

        /** Whether the class's `canEqual` is the one scalac writes: `aload_1; instanceof <Class>; ireturn`. */
        val hasGeneratedCanEqual: Boolean
            get() = shape.methods["canEqual" to "(L$OBJECT;)Z"]?.takeIf { !it.isStatic }?.body == generatedCanEqual(name)

        /**
         * Whether scalac may have left the `canEqual` call out of this class's `equals`. It does so
         * when the class is final and its `canEqual` is scalac's own: Scala 2.13.15's `equalsCore`
         * tests `clazz.isFinal && syntheticCanEqual`, and Scala 3.3.4's
         * `SyntheticMembers.equalsBody` keeps the call unless the class is final and declares no
         * `canEqual` of the adopter's. Bytecode shows only that a `canEqual` has scalac's body, and
         * an adopter's `o.isInstanceOf[UC]` compiles to that same body while scalac still calls it
         * (`UC`), so for a final class with such a `canEqual` the call may be present or absent. In
         * every other class it is present (`UC2`, whose `canEqual` is `false`).
         */
        val equalsMayOmitCanEqual: Boolean get() = shape.isFinal && hasGeneratedCanEqual
    }

    /**
     * The plumbing scalac 3 writes for an `enum`, read from `javap -c -p` over `Suit`, `Planet`,
     * `Shape`, `Level`, `EnumHost.Mode`, `Hue` and `Color` in `Enums.scala`, `Switches.scala` and
     * `Scala3Only.scala`. Four kinds of class carry it, told apart by structure only scalac writes:
     *
     * - The enum class: abstract, `ACC_ENUM`, extending `Object` and implementing `scala.reflect.Enum`
     *   ([enumClassPlumbing]).
     * - Its companion module class, which implements `scala.deriving.Mirror$Sum` and whose partner is an
     *   enum class ([enumCompanionPlumbing]).
     * - The class of a singleton case: final, implementing `scala.runtime.EnumValue` and
     *   `scala.deriving.Mirror$Singleton`, extending an enum class ([singletonCasePlumbing]).
     * - A parameterised case, a final case class extending an enum class ([parameterisedCasePlumbing]).
     *   Only its `ordinal` is enum plumbing; the rest is the case class rules' to mark.
     *
     * The enum class and the companion are read together: every question about a case's name or ordinal
     * is answered from the companion's case fields in declaration order, the `ACC_ENUM` field of a
     * singleton case and the field holding a parameterised case's companion ([enumCases]).
     */
    private fun enumPlumbing(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): Set<Pair<String, String>> {
        if (!shape.isScala || !shape.isScala3) return emptySet()
        if (shape.isEnumClass()) return enumClassPlumbing(shape)
        if (shape.isModuleClass && MIRROR_SUM in shape.interfaces) {
            val enumClass = readClass(shape.internalName.removeSuffix("$"), lookup)?.takeIf { it.isEnumClass() } ?: return emptySet()
            return enumCompanionPlumbing(EnumFamily(enumClass, shape, lookup), lookup)
        }
        val singleton = ENUM_VALUE in shape.interfaces && MIRROR_SINGLETON in shape.interfaces && shape.isFinal
        if (!singleton && !shape.declaresConstantOrdinal()) return emptySet()
        val superName = shape.superName ?: return emptySet()
        val enumClass = readClass(superName, lookup)?.takeIf { it.isEnumClass() } ?: return emptySet()
        val family = enumFamilyOf(enumClass, lookup) ?: return emptySet()
        return if (singleton) singletonCasePlumbing(shape, family) else parameterisedCasePlumbing(shape, family)
    }

    /** Whether the class is a Scala 3 enum class: see [enumPlumbing]. */
    private fun ClassShape.isEnumClass(): Boolean =
        access and Opcodes.ACC_ENUM != 0 &&
            access and Opcodes.ACC_ABSTRACT != 0 &&
            superName == OBJECT &&
            ENUM in interfaces

    /**
     * The fields that are the cases of the enum [enumName], in declaration order, which is ordinal
     * order. A singleton case's field has `ACC_ENUM`. A parameterised case's field does not; it holds
     * the case's companion, `<enum>$<case>$`, and is a case only when the class `<enum>$<case>`
     * extends the enum: scalac writes a field of the same shape for a nested object, a nested case
     * class's companion or a `given ... with` instance in the companion, which are not cases.
     */
    private fun ClassShape.enumCases(
        enumName: String,
        lookup: (String) -> ByteArray?,
    ): List<FieldShape> =
        fields.filter {
            it.access and PLAIN_CASE_FIELD_FLAGS == PLAIN_CASE_FIELD_FLAGS &&
                it.name != MODULE_FIELD &&
                (
                    it.access and Opcodes.ACC_ENUM != 0 ||
                        (it.descriptor == "L$enumName\$${it.name}\$;" && superNameOf("$enumName\$${it.name}", lookup) == enumName)
                )
        }

    private fun ClassShape.declaresConstantOrdinal(): Boolean {
        val body = methods["ordinal" to "()I"]?.body ?: return false
        return body.size == 2 && body[0] is Insn.IntConstant && body[1] == Insn.Plain(Opcodes.IRETURN)
    }

    /** The superclass of [internalName], read from its class file's header alone; null when it cannot be read. */
    private fun superNameOf(
        internalName: String,
        lookup: (String) -> ByteArray?,
    ): String? =
        try {
            lookup(internalName)?.let { ClassReader(it).superName }
        } catch (_: Exception) {
            null
        }

    private fun readClass(
        internalName: String,
        lookup: (String) -> ByteArray?,
    ): ClassShape? =
        try {
            lookup(internalName)?.let(::readShape)
        } catch (_: Exception) {
            null
        }

    /** An enum class and its companion, with the cases the companion's fields name. */
    private class EnumFamily(
        val enumClass: ClassShape,
        val companion: ClassShape,
        lookup: (String) -> ByteArray?,
    ) {
        val cases: List<FieldShape> = companion.enumCases(enumClass.internalName, lookup)
        val singletonType: String = "L${enumClass.internalName};"

        /** Whether every case is a singleton, the shape that gives the companion a `$values` array. */
        val allSingleton: Boolean get() = cases.isNotEmpty() && cases.all { it.descriptor == singletonType }

        fun ordinalOf(name: String): Int = cases.indexOfFirst { it.name == name }
    }

    private fun enumFamilyOf(
        enumClass: ClassShape,
        lookup: (String) -> ByteArray?,
    ): EnumFamily? {
        val companion = readClass("${enumClass.internalName}$", lookup) ?: return null
        // An enum declared inside a class has a companion with no MODULE$, so a module class is not required.
        if (!companion.isScala3 || MIRROR_SUM !in companion.interfaces) return null
        return EnumFamily(enumClass, companion, lookup)
    }

    /**
     * The four `scala.Product` mixin forwarders of an enum class, each `Product`'s static method
     * called with `this`: `productIterator`, `productPrefix`, `productElementName` and
     * `productElementNames`. The static forwarders on the same class are marked
     * [GeneratedBy.STATIC_FORWARDER] first.
     */
    private fun enumClassPlumbing(shape: ClassShape): Set<Pair<String, String>> =
        shape
            .instanceMethods()
            .filter { (key, body) ->
                when (key) {
                    "productIterator" to "()$ITERATOR" -> {
                        body == productForwarder("productIterator\$", "(L$PRODUCT;)$ITERATOR")
                    }

                    "productPrefix" to "()L$STRING;" -> {
                        body == productForwarder("productPrefix\$", "(L$PRODUCT;)L$STRING;")
                    }

                    "productElementNames" to "()$ITERATOR" -> {
                        body == productForwarder("productElementNames\$", "(L$PRODUCT;)$ITERATOR")
                    }

                    "productElementName" to "(I)L$STRING;" -> {
                        body == productForwarder("productElementName\$", "(L$PRODUCT;I)L$STRING;", withIndex = true)
                    }

                    else -> {
                        false
                    }
                }
            }.mapTo(mutableSetOf()) { it.first }

    /**
     * The companion's `values`, `valueOf`, `fromOrdinal`, `$new` and `ordinal` (with its bridge), each
     * only when its body is the one scalac writes for this enum's cases. `writeReplace` is the object
     * rule's. An enum with a parameterised case gets no `values`, `valueOf` or `$values`, a `$new`
     * only when it has a singleton case, and a `fromOrdinal` that tests ordinals instead of indexing
     * the array.
     */
    private fun enumCompanionPlumbing(
        family: EnumFamily,
        lookup: (String) -> ByteArray?,
    ): Set<Pair<String, String>> {
        val companion = family.companion
        val enumType = family.singletonType
        val result = mutableSetOf<Pair<String, String>>()
        for ((key, method) in companion.methods) {
            if (method.isStatic) continue
            val (name, descriptor) = key
            val matched =
                when {
                    name == "values" && descriptor == "()[$enumType" -> {
                        matchesEnumValues(family, method)
                    }

                    name == "valueOf" && descriptor == "(L$STRING;)$enumType" -> {
                        matchesEnumValueOf(family, method)
                    }

                    name == "fromOrdinal" && descriptor == "(I)$enumType" -> {
                        matchesEnumFromOrdinal(family, method)
                    }

                    name == "\$new" && descriptor == "(IL$STRING;)$enumType" -> {
                        matchesEnumNew(family, method, lookup)
                    }

                    name == "ordinal" && descriptor == "($enumType)I" -> {
                        method.body ==
                            listOf(
                                aload(1),
                                Insn.Call(Opcodes.INVOKEVIRTUAL, family.enumClass.internalName, "ordinal", "()I"),
                                Insn.Plain(Opcodes.IRETURN),
                            )
                    }

                    name == "ordinal" && descriptor == "(L$OBJECT;)I" -> {
                        method.body ==
                            listOf(
                                aload(0),
                                aload(1),
                                Insn.TypeOperand(Opcodes.CHECKCAST, family.enumClass.internalName),
                                Insn.Call(Opcodes.INVOKEVIRTUAL, companion.internalName, "ordinal", "($enumType)I"),
                                Insn.Plain(Opcodes.IRETURN),
                            )
                    }

                    else -> {
                        false
                    }
                }
            if (matched) result += key
        }
        return result
    }

    /** `getstatic $values; invokevirtual <array>.clone; checkcast <array>; areturn`. */
    private fun matchesEnumValues(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val array = "[${family.singletonType}"
        val holder = family.companion.fields.singleOrNull { it.name == VALUES_FIELD && it.descriptor == array }
        return family.allSingleton &&
            holder != null &&
            method.body ==
            listOf(
                Insn.Field(Opcodes.GETSTATIC, family.companion.internalName, VALUES_FIELD, array),
                Insn.Call(Opcodes.INVOKEVIRTUAL, array, "clone", "()L$OBJECT;"),
                Insn.TypeOperand(Opcodes.CHECKCAST, array),
                Insn.Plain(Opcodes.ARETURN),
            )
    }

    /** The string-concatenation call that builds scalac's `has no case with <what>` message. */
    private fun Match.noCaseMessage(
        family: EnumFamily,
        what: String,
    ) {
        val tail = " has no case with $what: \u0001"
        take { insn ->
            (insn as? Insn.Dynamic)?.takeIf { call ->
                val recipe = call.arguments.singleOrNull() as? String
                call.name == "makeConcatWithConstants" &&
                    call.descriptor == CONCAT_DESCRIPTOR &&
                    call.bootstrap.owner == STRING_CONCAT &&
                    call.bootstrap.name == "makeConcatWithConstants" &&
                    recipe != null &&
                    recipe.startsWith("enum ") &&
                    recipe.endsWith(tail) &&
                    recipe.removePrefix("enum ").removeSuffix(tail).substringAfterLast('.') == family.enumClass.sourceName
            }
        }
    }

    /**
     * `new <exception>; dup; <argument>; <message call>; invokespecial <init>(String); athrow`. Scala
     * 3.3.3 words both differently: `valueOf`'s message is `"enum case not found: " + s`, with no enum
     * name, and `fromOrdinal` passes the ordinal's string with no message call at all.
     */
    private fun Match.throwNoCase(
        family: EnumFamily,
        exception: String,
        what: String,
    ) {
        step(Insn.TypeOperand(Opcodes.NEW, exception))
        step(Insn.Plain(Opcodes.DUP))
        if (what == "name") {
            step(aload(1))
            either({ noCaseMessage(family, what) }, { concatWithRecipe(OLD_NO_CASE_RECIPE) })
        } else {
            step(Insn.Var(Opcodes.ILOAD, 1))
            step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxToInteger", "(I)Ljava/lang/Integer;"))
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, INTEGER, "toString", "()L$STRING;"))
            optional { noCaseMessage(family, what) }
        }
        step(Insn.Call(Opcodes.INVOKESPECIAL, exception, "<init>", "(L$STRING;)V"))
        step(Insn.Plain(Opcodes.ATHROW))
    }

    /** The string-concatenation call with exactly [recipe]. */
    private fun Match.concatWithRecipe(recipe: String) {
        take { insn ->
            (insn as? Insn.Dynamic)?.takeIf { call ->
                call.name == "makeConcatWithConstants" &&
                    call.descriptor == CONCAT_DESCRIPTOR &&
                    call.bootstrap.owner == STRING_CONCAT &&
                    call.bootstrap.name == "makeConcatWithConstants" &&
                    call.arguments.singleOrNull() == recipe
            }
        }
    }

    /** `aload_0; pop; getstatic <companion>.<case>; areturn`, the return of a matched case. */
    private fun Match.returnCase(
        family: EnumFamily,
        case: FieldShape,
    ) {
        step(aload(0))
        step(Insn.Plain(Opcodes.POP))
        step(Insn.Field(Opcodes.GETSTATIC, family.companion.internalName, case.name, case.descriptor))
        step(areturn())
    }

    /**
     * `valueOf(String)`, in the two lowerings of the string match it is. Both open `aload_1; astore s`.
     * With one or two cases, a chain per case in declaration order: `ldc <name>; aload s;
     * Object.equals; ifeq NEXT; <return the case>`. With three or more, a switch on the string's hash
     * (`aload s; ifnonnull H; iconst_0; goto S; H: aload s; String.hashCode; S:`), a `tableswitch` when
     * the hashes are dense (`case X, Y, Z`) and a `lookupswitch` otherwise, whose buckets are in key
     * order, each the same test followed by `goto DEFAULT`. The tail is `throw new IllegalArgumentException(
     * "enum <enum> has no case with name: " + s)`. Two cases whose names share a hash share a bucket,
     * a lowering not read here. An enum with a parameterised case has no `valueOf`.
     */
    private fun matchesEnumValueOf(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val body = method.body ?: return false
        if (!family.allSingleton) return false
        val cases = family.cases
        val chain =
            Match(body).also { m ->
                m.step(aload(1))
                m.store(Opcodes.ASTORE, "s")
                for ((index, case) in cases.withIndex()) {
                    m.step(Insn.Constant(case.name))
                    m.load(Opcodes.ALOAD, "s")
                    m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, OBJECT, "equals", "(L$OBJECT;)Z"))
                    m.jump(Opcodes.IFEQ, "next$index")
                    m.returnCase(family, case)
                    m.label("next$index")
                }
                m.throwNoCase(family, ILLEGAL_ARGUMENT, "name")
            }
        if (chain.matched) return true
        val byHash = cases.groupBy { it.name.hashCode() }
        if (byHash.values.any { it.size > 1 }) return false
        val keys = byHash.keys.sorted()
        return Match(body)
            .also { m ->
                m.step(aload(1))
                m.store(Opcodes.ASTORE, "s")
                m.load(Opcodes.ALOAD, "s")
                m.jump(Opcodes.IFNONNULL, "hash")
                m.step(Insn.IntConstant(0))
                m.jump(Opcodes.GOTO, "switch")
                m.label("hash")
                m.load(Opcodes.ALOAD, "s")
                m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, STRING, "hashCode", "()I"))
                m.label("switch")
                m.keyedSwitch(keys, keys.indices.map { "bucket$it" }, "default")
                for ((index, key) in keys.withIndex()) {
                    val case = byHash.getValue(key).single()
                    m.label("bucket$index")
                    m.step(Insn.Constant(case.name))
                    m.load(Opcodes.ALOAD, "s")
                    m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, OBJECT, "equals", "(L$OBJECT;)Z"))
                    m.jump(Opcodes.IFEQ, "miss$index")
                    m.returnCase(family, case)
                    m.label("miss$index")
                    m.jump(Opcodes.GOTO, "default")
                }
                m.label("default")
                m.throwNoCase(family, ILLEGAL_ARGUMENT, "name")
            }.matched
    }

    /**
     * `fromOrdinal(int)`, in two shapes. With only singleton cases it indexes `$values` inside a
     * `Throwable` handler that rethrows as `NoSuchElementException`: `getstatic $values; iload_1;
     * aaload; goto END; H: pop; <throw>; nop; nop; athrow; END: areturn`, the handler covering the
     * first three instructions. An enum with a parameterised case tests ordinals instead,
     * `iload_1; istore k` then, with one or two singleton cases, per case `<ordinal>; iload k;
     * if_icmpne NEXT; <return the case>`, and with three or more `iload k` and a switch on the
     * singleton ordinals whose targets return each case, before the throw. An enum with no singleton
     * case is only the throw. The ordinal of a case is its position among all the companion's case
     * fields, parameterised ones included.
     */
    private fun matchesEnumFromOrdinal(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val code = method.code ?: return false
        val array = "[${family.singletonType}"
        if (family.allSingleton && family.companion.fields.any { it.name == VALUES_FIELD && it.descriptor == array }) {
            val m = Match(code)
            m.step(Insn.Field(Opcodes.GETSTATIC, family.companion.internalName, VALUES_FIELD, array))
            m.step(Insn.Var(Opcodes.ILOAD, 1))
            m.step(Insn.Plain(Opcodes.AALOAD))
            m.jump(Opcodes.GOTO, "end")
            m.label("handler")
            m.step(Insn.Plain(Opcodes.POP))
            m.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal")
            m.step(Insn.Plain(Opcodes.NOP))
            m.step(Insn.Plain(Opcodes.NOP))
            m.step(Insn.Plain(Opcodes.ATHROW))
            m.label("end")
            m.step(areturn())
            val handler = m.labelIndex("handler")
            if (m.matched && handler != null && method.handlers == listOf(Handler(0, 3, handler, THROWABLE))) return true
        }
        if (method.hasHandler) return false
        val singletons = family.cases.withIndex().filter { (_, case) -> case.descriptor == family.singletonType }
        if (singletons.isEmpty()) {
            return Match(code).also { it.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal") }.matched
        }
        val chain = Match(code)
        chain.step(Insn.Var(Opcodes.ILOAD, 1))
        chain.store(Opcodes.ISTORE, "k")
        for ((ordinal, case) in singletons) {
            chain.step(Insn.IntConstant(ordinal))
            chain.load(Opcodes.ILOAD, "k")
            chain.jump(Opcodes.IF_ICMPNE, "next$ordinal")
            chain.returnCase(family, case)
            chain.label("next$ordinal")
        }
        chain.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal")
        if (chain.matched) return true
        val switch = Match(code)
        switch.step(Insn.Var(Opcodes.ILOAD, 1))
        switch.store(Opcodes.ISTORE, "k")
        switch.load(Opcodes.ILOAD, "k")
        switch.keyedSwitch(singletons.map { it.index }, singletons.map { "case${it.index}" }, "default")
        for ((ordinal, case) in singletons) {
            switch.label("case$ordinal")
            switch.returnCase(family, case)
        }
        switch.label("default")
        switch.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal")
        return switch.matched
    }

    /**
     * The private `$new(int, String)`: `new <singleton class>; dup; aload_2; iload_1; invokespecial
     * <init>(String, int); areturn`, where the class reads as the singleton-case class of this enum.
     * Scala 3.3.3, 3.4.0 and 3.4.1 also pass the companion: `aload_0` after `iload_1`, to
     * `<init>(String, int, <companion>)`.
     */
    private fun matchesEnumNew(
        family: EnumFamily,
        method: MethodShape,
        lookup: (String) -> ByteArray?,
    ): Boolean {
        val body = method.body ?: return false
        val created = (body.firstOrNull() as? Insn.TypeOperand)?.takeIf { it.opcode == Opcodes.NEW }?.type ?: return false
        val singleton = readClass(created, lookup)
        val companionType = "L${family.companion.internalName};"
        return method.isPrivate &&
            singleton != null &&
            singleton.isEnumSingletonOf(family.enumClass) &&
            (
                body ==
                    listOf(
                        Insn.TypeOperand(Opcodes.NEW, created),
                        Insn.Plain(Opcodes.DUP),
                        aload(2),
                        Insn.Var(Opcodes.ILOAD, 1),
                        Insn.Call(Opcodes.INVOKESPECIAL, created, "<init>", "(L$STRING;I)V"),
                        areturn(),
                    ) ||
                    body ==
                    listOf(
                        Insn.TypeOperand(Opcodes.NEW, created),
                        Insn.Plain(Opcodes.DUP),
                        aload(2),
                        Insn.Var(Opcodes.ILOAD, 1),
                        aload(0),
                        Insn.Call(Opcodes.INVOKESPECIAL, created, "<init>", "(L$STRING;I$companionType)V"),
                        areturn(),
                    )
            )
    }

    private fun ClassShape.isEnumSingletonOf(enumClass: ClassShape): Boolean =
        isScala3 && isFinal && superName == enumClass.internalName && ENUM_VALUE in interfaces && MIRROR_SINGLETON in interfaces

    /**
     * A singleton case's class. Its `canEqual`, `productArity`, `productElement` and
     * `productElementName` call `EnumValue`'s static twin with `this` (and the argument), its
     * `fromProduct` calls `Mirror$Singleton.fromProduct$` and has a bridge returning `Object`, and its
     * private `readResolve` is `getstatic <companion>.MODULE$; aload_0; invokevirtual ordinal;
     * invokevirtual <companion>.fromOrdinal; areturn`. The name and ordinal come in two forms. Cases
     * that share a class (`case A, B`) keep them in the fields `$name$N` and `_$ordinal$N` of one
     * suffix and read them with `getfield`; a case with its own class (`case A extends E(1)`) returns
     * constants, and the name must be a singleton case of the companion whose position is the
     * constant `ordinal` returns. `productPrefix` and `toString` return the name, and `hashCode`
     * (Scala 3.3.7 and later on the 3.3 line, 3.7.3 and later) is `String.hashCode` of it.
     */
    private fun singletonCasePlumbing(
        shape: ClassShape,
        family: EnumFamily,
    ): Set<Pair<String, String>> {
        if (!shape.isEnumSingletonOf(family.enumClass)) return emptySet()
        val self = shape.internalName
        val companion = family.companion.internalName
        val enumType = family.singletonType
        val enumValue = "L$ENUM_VALUE;"
        val product = "L$PRODUCT;"
        val singleton = "L$MIRROR_SINGLETON;"
        val result = mutableSetOf<Pair<String, String>>()

        fun forward(
            loads: List<Insn>,
            owner: String,
            name: String,
            descriptor: String,
        ): List<Insn> =
            loads +
                Insn.Call(Opcodes.INVOKESTATIC, owner, name, descriptor) +
                Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))

        val forwarders =
            mapOf(
                ("canEqual" to "(L$OBJECT;)Z") to
                    forward(listOf(aload(0), aload(1)), ENUM_VALUE, "canEqual\$", "(${enumValue}L$OBJECT;)Z"),
                ("productArity" to "()I") to forward(listOf(aload(0)), ENUM_VALUE, "productArity\$", "($enumValue)I"),
                ("productElement" to "(I)L$OBJECT;") to
                    forward(listOf(aload(0), Insn.Var(Opcodes.ILOAD, 1)), ENUM_VALUE, "productElement\$", "(${enumValue}I)L$OBJECT;"),
                ("productElementName" to "(I)L$STRING;") to
                    forward(listOf(aload(0), Insn.Var(Opcodes.ILOAD, 1)), ENUM_VALUE, "productElementName\$", "(${enumValue}I)L$STRING;"),
                ("fromProduct" to "($product)$singleton") to
                    forward(listOf(aload(0), aload(1)), MIRROR_SINGLETON, "fromProduct\$", "($singleton$product)$singleton"),
                ("fromProduct" to "($product)L$OBJECT;") to
                    listOf(aload(0), aload(1), Insn.Call(Opcodes.INVOKEVIRTUAL, self, "fromProduct", "($product)$singleton"), areturn()),
            )
        for ((key, expected) in forwarders) {
            if (shape.methods[key]?.takeIf { !it.isStatic }?.body == expected) result += key
        }
        val readResolve = shape.methods[READ_RESOLVE]
        val resolves =
            listOf(
                Insn.Field(Opcodes.GETSTATIC, companion, MODULE_FIELD, "L$companion;"),
                aload(0),
                Insn.Call(Opcodes.INVOKEVIRTUAL, self, "ordinal", "()I"),
                Insn.Call(Opcodes.INVOKEVIRTUAL, companion, "fromOrdinal", "(I)$enumType"),
                areturn(),
            )
        if (readResolve != null && readResolve.isPrivate && readResolve.body == resolves) result += READ_RESOLVE

        val nameLoad = singletonNameLoad(shape, family) ?: return result
        val ordinalKey = "ordinal" to "()I"
        val ordinalBody = shape.methods[ordinalKey]?.takeIf { !it.isStatic }?.body
        val ordinalMatches =
            when (val first = nameLoad.first()) {
                is Insn.Constant -> {
                    ordinalBody ==
                        listOf(Insn.IntConstant(family.ordinalOf(first.value as String)), Insn.Plain(Opcodes.IRETURN))
                }

                else -> {
                    ordinalBody?.let { singletonOrdinalLoad(shape, nameLoad, it) } == true
                }
            }
        if (ordinalMatches) result += ordinalKey
        val prefix = nameLoad + areturn()
        if (shape.methods["productPrefix" to "()L$STRING;"]?.takeIf { !it.isStatic }?.body ==
            prefix
        ) {
            result += "productPrefix" to "()L$STRING;"
        }
        if (shape.methods["toString" to "()L$STRING;"]?.takeIf { !it.isStatic }?.body == prefix) result += "toString" to "()L$STRING;"
        val hash = nameLoad + Insn.Call(Opcodes.INVOKEVIRTUAL, STRING, "hashCode", "()I") + Insn.Plain(Opcodes.IRETURN)
        if (shape.methods["hashCode" to "()I"]?.takeIf { !it.isStatic }?.body == hash) result += "hashCode" to "()I"
        return result
    }

    /**
     * The instructions that push a singleton case's name: `ldc <name>` for a name that is a singleton
     * case of the companion, or `aload_0; getfield $name$N` of a private final `String` field. Null
     * for anything else.
     */
    private fun singletonNameLoad(
        shape: ClassShape,
        family: EnumFamily,
    ): List<Insn>? {
        val body = shape.methods["productPrefix" to "()L$STRING;"]?.takeIf { !it.isStatic }?.body ?: return null
        if (body.size == 2 && body[1] == areturn()) {
            val name = (body[0] as? Insn.Constant)?.value as? String ?: return null
            val index = family.ordinalOf(name)
            return if (index >= 0 && family.cases[index].descriptor == family.singletonType) listOf(body[0]) else null
        }
        val field = body.getOrNull(1) as? Insn.Field ?: return null
        val declared = shape.fields.singleOrNull { it.name == field.name && it.descriptor == "L$STRING;" } ?: return null
        val instanceField =
            declared.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) ==
                (Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)
        return if (instanceField &&
            field.name.startsWith("\$name\$") &&
            body == listOf(aload(0), Insn.Field(Opcodes.GETFIELD, shape.internalName, field.name, "L$STRING;"), areturn())
        ) {
            body.take(2)
        } else {
            null
        }
    }

    /** Whether [body] reads the `_$ordinal$N` field whose suffix is the one of the `$name$N` field [nameLoad] reads. */
    private fun singletonOrdinalLoad(
        shape: ClassShape,
        nameLoad: List<Insn>,
        body: List<Insn>,
    ): Boolean {
        val suffix = (nameLoad.last() as Insn.Field).name.removePrefix("\$name\$")
        val field = "_\$ordinal\$$suffix"
        val declared = shape.fields.singleOrNull { it.name == field && it.descriptor == "I" } ?: return false
        return declared.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) ==
            (Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL) &&
            body == listOf(aload(0), Insn.Field(Opcodes.GETFIELD, shape.internalName, field, "I"), Insn.Plain(Opcodes.IRETURN))
    }

    /**
     * A parameterised case's `ordinal`: `<n>; ireturn`, where the companion's `n`th case is this
     * class's own source name, held in a field of this class's module class.
     */
    private fun parameterisedCasePlumbing(
        shape: ClassShape,
        family: EnumFamily,
    ): Set<Pair<String, String>> {
        if (!shape.isFinal || ENUM_VALUE in shape.interfaces) return emptySet()
        val index = family.ordinalOf(shape.sourceName)
        val key = "ordinal" to "()I"
        val fits = index >= 0 && family.cases[index].descriptor == "L${shape.internalName}$;"
        return if (fits &&
            shape.methods[key]?.body == listOf(Insn.IntConstant(index), Insn.Plain(Opcodes.IRETURN))
        ) {
            setOf(key)
        } else {
            emptySet()
        }
    }

    private fun generatedCanEqual(self: String): List<Insn> =
        listOf(aload(1), Insn.TypeOperand(Opcodes.INSTANCEOF, self), Insn.Plain(Opcodes.IRETURN))

    private fun caseClassOf(shape: ClassShape): CaseClass? =
        if (shape.isScala && CASE_CLASS_SIGNATURE.all { shape.methods[it]?.isStatic == false }) CaseClass(shape) else null

    /**
     * The plumbing methods of [case]: each instance method whose name and descriptor are one of the
     * members below and whose body is the one scalac writes for it in this class. A field
     * accessor is never one; the adopter declared it. Neither is a member the adopter overrode,
     * unless the override is instruction for instruction what scalac writes, in which case it is
     * the generated code. A shape only one Scala version writes counts only in a class of that
     * version (see [readShape]). Shapes, read from `javap -c` over both versions:
     *
     * - `canEqual`: `aload_1; instanceof <Class>; ireturn`.
     * - `productPrefix`: `ldc "<source name>"; areturn`.
     * - `productArity`: the element count; `ireturn`. Marked only when `productElement` is marked
     *   too, since the bounds of its dispatch carry the count scalac knew: a hand-written
     *   `productArity` returning fewer than the elements (`Lie2`) finds that many stored fields and
     *   would otherwise pass. A class whose elements are unknown has none.
     * - `productIterator`: `ScalaRunTime$.typedProductIterator(this)` (2.13) or `Product.productIterator$(this)` (3).
     * - `productElementNames`: `Product.productElementNames$(this)`, in both.
     * - `toString`: `ScalaRunTime$._toString(this)`; for a case object `ldc "<source name>"; areturn`.
     * - `hashCode`: see [matchesHashCode].
     * - `equals`: see [matchesEqualsScala2] and [matchesEqualsScala3].
     * - `productElement` and `productElementName`: see [matchesIndexed]. A Scala 2 case object's
     *   `productElementName` is `Product.productElementName$(this, n)` instead.
     * - `copy`: see [matchesConstruction].
     * - Scala 3's `_N()`: `aload_0`, the element's read, its return. Only in a Scala 3 class (see
     *   [readShape]), since Scala 2.13.15 never writes one.
     * - Scala 2's `<name>$access$<index>()`: `aload_0; getfield <name>`, its return. Only in a
     *   Scala 2 class, since Scala 3.3.4 never writes one.
     */
    private fun caseClassPlumbing(case: CaseClass): Set<Pair<String, String>> {
        val self = case.name
        val scala3 = case.shape.isScala3
        val sourceName = listOf(Insn.Constant(case.shape.sourceName), areturn())
        val productElementMatches =
            case.shape.methods["productElement" to "(I)L$OBJECT;"]
                ?.takeIf { !it.isStatic }
                ?.body
                ?.let { matchesIndexed(case, it, names = false) } == true
        return case.shape
            .instanceMethods()
            .filter { (key, body) ->
                val (name, descriptor) = key
                when (key) {
                    "canEqual" to "(L$OBJECT;)Z" -> {
                        body == generatedCanEqual(self)
                    }

                    "productPrefix" to "()L$STRING;" -> {
                        body == sourceName
                    }

                    "productArity" to "()I" -> {
                        case.arity != null && case.elements?.size == case.arity && productElementMatches
                    }

                    "productIterator" to "()$ITERATOR" -> {
                        if (scala3) {
                            body == productForwarder("productIterator\$", "(L$PRODUCT;)$ITERATOR")
                        } else {
                            body == runtimeCall("typedProductIterator", "(L$PRODUCT;)$ITERATOR")
                        }
                    }

                    "productElementNames" to "()$ITERATOR" -> {
                        body == productForwarder("productElementNames\$", "(L$PRODUCT;)$ITERATOR")
                    }

                    "toString" to "()L$STRING;" -> {
                        if (case.isObject) body == sourceName else body == runtimeCall("_toString", "(L$PRODUCT;)L$STRING;")
                    }

                    "hashCode" to "()I" -> {
                        matchesHashCode(case, body)
                    }

                    "equals" to "(L$OBJECT;)Z" -> {
                        !case.isObject && if (scala3) matchesEqualsScala3(case, body) else matchesEqualsScala2(case, body)
                    }

                    "productElement" to "(I)L$OBJECT;" -> {
                        productElementMatches
                    }

                    "productElementName" to "(I)L$STRING;" -> {
                        matchesIndexed(case, body, names = true) ||
                            (
                                !scala3 &&
                                    case.isObject &&
                                    body == productForwarder("productElementName\$", "(L$PRODUCT;I)L$STRING;", withIndex = true)
                            )
                    }

                    else -> {
                        when {
                            name == "copy" && Type.getReturnType(descriptor).descriptor == "L$self;" -> {
                                !case.isObject &&
                                    matchesConstruction(body, self, descriptor, case.shape, outerOwner = self, outerAccessor = true)
                            }

                            ELEMENT_ALIAS.matches(name) -> {
                                case.shape.isScala3 && matchesElementAlias(case, name, descriptor, body)
                            }

                            ACCESS_ACCESSOR.matches(name) -> {
                                !case.shape.isScala3 && matchesAccessAccessor(case, name, descriptor, body)
                            }

                            else -> {
                                false
                            }
                        }
                    }
                }
            }.mapTo(mutableSetOf()) { it.first }
    }

    /** `getstatic ScalaRunTime$.MODULE$; aload_0; invokevirtual ScalaRunTime$.<name>`, and the return for its result. */
    private fun runtimeCall(
        name: String,
        descriptor: String,
    ): List<Insn> =
        listOf(
            Insn.Field(Opcodes.GETSTATIC, RUNTIME, MODULE_FIELD, "L$RUNTIME;"),
            aload(0),
            Insn.Call(Opcodes.INVOKEVIRTUAL, RUNTIME, name, descriptor),
            Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)),
        )

    /** A mixin forwarder to `scala.Product`'s static `<name>`, passing `this` and, [withIndex], the `int` argument. */
    private fun productForwarder(
        name: String,
        descriptor: String,
        withIndex: Boolean = false,
    ): List<Insn> =
        listOfNotNull(
            aload(0),
            if (withIndex) Insn.Var(Opcodes.ILOAD, 1) else null,
            Insn.Call(Opcodes.INVOKESTATIC, PRODUCT, name, descriptor),
            areturn(),
        )

    /** Scala 3's `_N()`: `aload_0`, a read of element `N - 1`, and the return for its type. */
    private fun matchesElementAlias(
        case: CaseClass,
        name: String,
        descriptor: String,
        body: List<Insn>,
    ): Boolean {
        val index =
            ELEMENT_ALIAS
                .matchEntire(name)!!
                .groupValues[1]
                .toIntOrNull()
                ?.minus(1) ?: return false
        val element = case.elements?.getOrNull(index) ?: return false
        return descriptor == "()${element.type}" &&
            body == listOf(aload(0), element.read, Insn.Plain(element.type.getOpcode(Opcodes.IRETURN)))
    }

    /** Scala 2's `<name>$access$<index>()`: `aload_0; getfield <name>` and the return for its type. */
    private fun matchesAccessAccessor(
        case: CaseClass,
        name: String,
        descriptor: String,
        body: List<Insn>,
    ): Boolean {
        val groups = ACCESS_ACCESSOR.matchEntire(name)!!.groupValues
        val element = case.elements?.getOrNull(groups[2].toIntOrNull() ?: return false) ?: return false
        return element.name == groups[1] &&
            element.accessor == name &&
            descriptor == "()${element.type}" &&
            body ==
            listOf(
                aload(0),
                Insn.Field(Opcodes.GETFIELD, case.name, element.name, element.type.descriptor),
                Insn.Plain(element.type.getOpcode(Opcodes.IRETURN)),
            )
    }

    /**
     * Whether [body] is `new <target>; dup`, optionally the outer reference, every parameter of
     * [descriptor] loaded in order from slot 1, `invokespecial <target>.<init>` taking the outer
     * reference's type and those parameters, and `areturn`; and that constructor is [targetShape]'s
     * primary one (see [ClassShape.isPrimaryConstructor]), so that a hand-written `copy` or
     * `apply` that builds through an auxiliary constructor is left alone. This is a case class's
     * `copy` and a companion's `apply` in both versions. The
     * outer reference of an inner or local case class is `aload_0; getfield <outerOwner>.$outer`,
     * or, in Scala 2.13.15's `copy` of an inner class, `aload_0; invokevirtual <outerOwner>.<...>$$outer()`
     * when [outerAccessor] allows it (`javap` over `Outer$Inner` and the local `Local`).
     */
    private fun matchesConstruction(
        body: List<Insn>,
        target: String,
        descriptor: String,
        targetShape: ClassShape,
        outerOwner: String,
        outerAccessor: Boolean,
    ): Boolean {
        val m = Match(body)
        m.step(Insn.TypeOperand(Opcodes.NEW, target))
        m.step(Insn.Plain(Opcodes.DUP))
        val outer = m.optional { outerReference(outerOwner, outerAccessor) }
        for (load in parameterLoads(descriptor, firstSlot = 1)) m.step(load)
        val constructor = "(${outer.orEmpty()}${parameterPart(descriptor)})V"
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, target, "<init>", constructor))
        m.step(areturn())
        return m.matched && targetShape.isPrimaryConstructor(constructor)
    }

    /**
     * Reads an inner class's outer reference from `this`: `aload_0` then `getfield
     * <owner>.$outer`, or, when [accessor] allows it, `invokevirtual <owner>.<name>()` for a method
     * whose name ends in `$$outer`. Returns the reference's descriptor.
     */
    private fun Match.outerReference(
        owner: String,
        accessor: Boolean,
    ): String? {
        step(aload(0))
        return take { insn ->
            when {
                insn is Insn.Field && insn.opcode == Opcodes.GETFIELD && insn.owner == owner && insn.name == OUTER_FIELD -> insn.descriptor
                accessor && insn is Insn.Call && insn.isOuterAccessor(owner) -> Type.getReturnType(insn.descriptor).descriptor
                else -> null
            }
        }
    }

    private fun Insn.Call.isOuterAccessor(owner: String): Boolean =
        opcode == Opcodes.INVOKEVIRTUAL && this.owner == owner && name.endsWith("\$\$outer") && descriptor.startsWith("()L")

    /**
     * `hashCode`, in three shapes read from both versions. A case object's is `ldc <hash of its
     * source name>; ireturn` (`Solo$`: 2582783, `"Solo".hashCode()`). A case class with no element
     * of a primitive type, `Empty` and `One` among them, has `ScalaRunTime$._hashCode(this)`. Any
     * other case class has scalac's fold: seed `0xcafebabe`, `Statics.mix` with
     * `productPrefix().hashCode()`, one `Statics.mix` per element in order, and
     * `Statics.finalizeHash(acc, <arity>)`. Each element is read through its accessor and hashed
     * with `Statics.longHash`, `doubleHash` or `floatHash` for `long`, `double` and `float`,
     * `anyHash` for a reference, `1231` or `1237` for a `boolean`, and as it is for `int`, `char`,
     * `byte` and `short` (`javap` over `Mixed`, `Hidden` and `Priv`).
     *
     * Three releases change that, each read with `javap` over the fixtures:
     *
     * - Scala 2.12.20 leaves the `productPrefix` mix out of the fold: the seed is stored and the
     *   first element's mix follows it. That is read only in a class 2.12 may have written
     *   ([CaseClass.mayBeScala212]).
     * - Scala 2.13.17 and later, Scala 3.3.7 and later on the 3.3 line, and 3.7.1 and later (3.3.4
     *   to 3.3.6, 3.4 to 3.6 and 3.7.0 write the older form), fold the prefix mix's argument into a constant, `productPrefix().hashCode()`
     *   evaluated by the compiler (`sipush 2176` for `Cc`). A case class with no element is then
     *   `ldc <that constant>; ireturn` (`Empty`: 67081517), and one with no primitive element is
     *   `MurmurHash3$.productHash(this, <seed mixed with that constant>, true)`: `getstatic
     *   MurmurHash3$.MODULE$; aload_0; ldc <constant>; iconst_1; invokevirtual productHash(Product,
     *   int, boolean)`. The constant there is `MurmurHash3.mix(0xcafebabe, productPrefix().hashCode())`,
     *   computed here, so a different literal is not scalac's.
     */
    private fun matchesHashCode(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean {
        if (case.isObject) return body == listOf(Insn.IntConstant(case.shape.sourceName.hashCode()), Insn.Plain(Opcodes.IRETURN))
        val elements = case.elements ?: return false
        val prefixHash = case.shape.sourceName.hashCode()
        if (elements.none { it.isPrimitive }) {
            return body == runtimeCall("_hashCode", "(L$PRODUCT;)I") ||
                if (elements.isEmpty()) {
                    body == listOf(Insn.IntConstant(prefixHash), Insn.Plain(Opcodes.IRETURN))
                } else {
                    body ==
                        listOf(
                            Insn.Field(Opcodes.GETSTATIC, MURMUR, MODULE_FIELD, "L$MURMUR;"),
                            aload(0),
                            Insn.IntConstant(murmurMix(HASH_SEED, prefixHash)),
                            Insn.IntConstant(1),
                            Insn.Call(Opcodes.INVOKEVIRTUAL, MURMUR, "productHash", "(L$PRODUCT;IZ)I"),
                            Insn.Plain(Opcodes.IRETURN),
                        )
                }
        }
        val mix = Insn.Call(Opcodes.INVOKESTATIC, STATICS, "mix", "(II)I")
        val m = Match(body, firstLocal = 1)
        m.step(Insn.IntConstant(HASH_SEED))
        m.store(Opcodes.ISTORE, "acc")
        val prefixMix: Match.() -> Unit = {
            load(Opcodes.ILOAD, "acc")
            either(
                {
                    step(aload(0))
                    step(Insn.Call(Opcodes.INVOKEVIRTUAL, case.name, "productPrefix", "()L$STRING;"))
                    step(Insn.Call(Opcodes.INVOKEVIRTUAL, STRING, "hashCode", "()I"))
                },
                { step(Insn.IntConstant(prefixHash)) },
            )
            step(mix)
            store(Opcodes.ISTORE, "acc")
        }
        // Only Scala 2.12 leaves the prefix mix out.
        if (case.mayBeScala212) m.optional(prefixMix) else m.prefixMix()
        for (element in elements) {
            m.load(Opcodes.ILOAD, "acc")
            m.step(aload(0))
            m.step(element.read)
            when (element.type.sort) {
                Type.BOOLEAN -> {
                    m.jump(Opcodes.IFEQ, "false${element.index}")
                    m.step(Insn.IntConstant(1231))
                    m.jump(Opcodes.GOTO, "mix${element.index}")
                    m.label("false${element.index}")
                    m.step(Insn.IntConstant(1237))
                    m.label("mix${element.index}")
                }

                Type.INT, Type.CHAR, Type.BYTE, Type.SHORT -> {
                    Unit
                }

                Type.LONG -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "longHash", "(J)I"))
                }

                Type.DOUBLE -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "doubleHash", "(D)I"))
                }

                Type.FLOAT -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "floatHash", "(F)I"))
                }

                Type.OBJECT, Type.ARRAY -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "anyHash", "(L$OBJECT;)I"))
                }

                else -> {
                    return false
                }
            }
            m.step(mix)
            m.store(Opcodes.ISTORE, "acc")
        }
        m.load(Opcodes.ILOAD, "acc")
        m.step(Insn.IntConstant(elements.size))
        m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "finalizeHash", "(II)I"))
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /** `Statics.mix` and `MurmurHash3.mix`: a Murmur3 step of [hash] with [data], then the rotate and multiply that close it. */
    private fun murmurMix(
        hash: Int,
        data: Int,
    ): Int {
        var k = data * MURMUR_C1
        k = Integer.rotateLeft(k, 15)
        k *= MURMUR_C2
        return Integer.rotateLeft(hash xor k, 13) * 5 + MURMUR_ADD
    }

    private const val MURMUR_C1 = 0xcc9e2d51L.toInt()
    private const val MURMUR_C2 = 0x1b873593
    private const val MURMUR_ADD = 0xe6546b64L.toInt()

    /**
     * The element comparisons of `equals`: every element of a primitive type first, then every
     * other one when [primitivesFirst], each in element order, each read through its accessor from
     * `this` and from the other instance in slot [other] (`javap` over `Mixed`, whose `String`
     * element comes before its `int` in the source and after it here). Scala 2.12.20 compares in
     * source order instead, so [primitivesFirst] is false for it. A mismatch jumps to [fail]:
     * `if_icmpne` for `int`, `boolean`, `char`, `byte` and `short`; `lcmp`, `dcmpl` or `fcmpl` then
     * `ifne` for `long`, `double` and `float`; for a reference, either `BoxesRunTime.equals` then
     * `ifeq`, which scalac writes for a generic element, or the null-safe `Object.equals` it writes
     * for `String` and `Option` elements. Scala 3.3.8, 3.8.4 and later write `java.util.Objects.equals`
     * then `ifeq` for the null-safe case instead, which [objectsEquals] allows.
     */
    private fun Match.compareElements(
        elements: List<Element>,
        other: String,
        fail: String,
        primitivesFirst: Boolean = true,
        objectsEquals: Boolean = false,
    ) {
        for (element in if (primitivesFirst) elements.filter { it.isPrimitive } + elements.filterNot { it.isPrimitive } else elements) {
            val i = element.index
            step(aload(0))
            step(element.read)
            load(Opcodes.ALOAD, other)
            step(element.read)
            when (element.type.sort) {
                Type.INT, Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT -> {
                    jump(Opcodes.IF_ICMPNE, fail)
                }

                Type.LONG, Type.DOUBLE, Type.FLOAT -> {
                    step(
                        Insn.Plain(
                            when (element.type.sort) {
                                Type.LONG -> Opcodes.LCMP
                                Type.DOUBLE -> Opcodes.DCMPL
                                else -> Opcodes.FCMPL
                            },
                        ),
                    )
                    jump(Opcodes.IFNE, fail)
                }

                Type.OBJECT, Type.ARRAY -> {
                    either(
                        {
                            step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "equals", "(L$OBJECT;L$OBJECT;)Z"))
                            jump(Opcodes.IFEQ, fail)
                        },
                        {
                            if (objectsEquals) {
                                either(
                                    {
                                        step(Insn.Call(Opcodes.INVOKESTATIC, OBJECTS, "equals", "(L$OBJECT;L$OBJECT;)Z"))
                                        jump(Opcodes.IFEQ, fail)
                                    },
                                    { nullSafeEquals(i, fail) },
                                )
                            } else {
                                nullSafeEquals(i, fail)
                            }
                        },
                    )
                }

                else -> {
                    fail()
                }
            }
        }
    }

    /** The null-safe `equals` of a reference element, as 2.13.15 and 3.3.4 inline it; [i] keeps its labels apart. */
    private fun Match.nullSafeEquals(
        i: Int,
        fail: String,
    ) {
        store(Opcodes.ASTORE, "that$i")
        step(Insn.Plain(Opcodes.DUP))
        jump(Opcodes.IFNONNULL, "nonNull$i")
        step(Insn.Plain(Opcodes.POP))
        load(Opcodes.ALOAD, "that$i")
        jump(Opcodes.IFNULL, "next$i")
        jump(Opcodes.GOTO, fail)
        label("nonNull$i")
        load(Opcodes.ALOAD, "that$i")
        step(Insn.Call(Opcodes.INVOKEVIRTUAL, OBJECT, "equals", "(L$OBJECT;)Z"))
        jump(Opcodes.IFEQ, fail)
        label("next$i")
    }

    /**
     * Scala 2.13.15's `equals`, read from `javap -c` over `Mixed`, `One`, `Priv`, `Empty`,
     * `Outer$Inner` and the final `FT`, `F1`, `FE`, `FinalOuter$FIn` and `FinalHolder$FObj`:
     *
     * ```
     * aload_0; aload_1; if_acmpeq TRUE          (absent with no elements)
     * aload_1; astore a; aload a; instanceof X; ifeq NO
     * [aload a; checkcast X; <outer>; aload_0; <outer>; if_acmpne NO]   (an inner class)
     * [iconst_1; ifeq NO]                       (an inner class, when X is final)
     * iconst_1; goto TEST
     * NO: goto NO2
     * NO2: iconst_0; goto TEST
     * TEST: ifeq FALSE
     * aload_1; checkcast X; astore b            (with no elements: aload_1; checkcast X, and no b)
     * <comparisons against b, failing to FALSE>
     * aload b; aload_0; invokevirtual canEqual; ifeq FALSE   (may be absent; see [CaseClass.equalsMayOmitCanEqual])
     * TRUE: iconst_1; goto END
     * FALSE: iconst_0
     * END: ireturn
     * ```
     *
     * A final class with no elements has its own shape instead, whatever its `canEqual`
     * (`FE`, and `FZC`, which declares its own): `aload_1; astore a; aload a; instanceof X;
     * ifeq NO; [iconst_1; ifeq NO]; iconst_1; ireturn; NO: goto NO2; NO2: iconst_0; ireturn`, the
     * bracketed part only for an inner class (`FinalOuter$FInEmpty`).
     *
     * Scala 2.12.20 writes the same bodies but compares the elements in source order, so a
     * reference element before a primitive one keeps its place (`Mixed`); [matchesEqualsScala2]
     * tries both orders.
     */
    private fun matchesEqualsScala2(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean =
        listOf(true, false).any { primitivesFirst ->
            (primitivesFirst || case.mayBeScala212) && matchesEqualsScala2(case, body, primitivesFirst)
        }

    private fun matchesEqualsScala2(
        case: CaseClass,
        body: List<Insn>,
        primitivesFirst: Boolean,
    ): Boolean {
        val elements = case.elements ?: return false
        val self = case.name
        val final = case.shape.isFinal
        val m = Match(body)
        if (final && elements.isEmpty()) {
            m.step(aload(1))
            m.store(Opcodes.ASTORE, "a")
            m.load(Opcodes.ALOAD, "a")
            m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
            m.jump(Opcodes.IFEQ, "no")
            if (case.shape.outerFieldDescriptor != null) {
                m.optional {
                    step(Insn.IntConstant(1))
                    jump(Opcodes.IFEQ, "no")
                }
            }
            m.step(Insn.IntConstant(1))
            m.step(Insn.Plain(Opcodes.IRETURN))
            m.label("no")
            m.jump(Opcodes.GOTO, "no2")
            m.label("no2")
            m.step(Insn.IntConstant(0))
            m.step(Insn.Plain(Opcodes.IRETURN))
            return m.matched
        }
        if (elements.isNotEmpty()) {
            m.step(aload(0))
            m.step(aload(1))
            m.jump(Opcodes.IF_ACMPEQ, "true")
        }
        m.step(aload(1))
        m.store(Opcodes.ASTORE, "a")
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
        m.jump(Opcodes.IFEQ, "no")
        if (final) {
            m.optional {
                step(Insn.IntConstant(1))
                jump(Opcodes.IFEQ, "no")
            }
        } else {
            m.optional {
                load(Opcodes.ALOAD, "a")
                step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
                take { insn -> (insn as? Insn.Call)?.takeIf { it.isOuterAccessor(self) } }?.let { accessor ->
                    step(aload(0))
                    step(accessor)
                }
                jump(Opcodes.IF_ACMPNE, "no")
            }
        }
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "test")
        m.label("no")
        m.jump(Opcodes.GOTO, "no2")
        m.label("no2")
        m.step(Insn.IntConstant(0))
        m.jump(Opcodes.GOTO, "test")
        m.label("test")
        m.jump(Opcodes.IFEQ, "false")
        m.step(aload(1))
        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
        if (elements.isNotEmpty()) {
            m.store(Opcodes.ASTORE, "b")
            m.compareElements(elements, "b", "false", primitivesFirst)
        }
        val canEqualCall: Match.() -> Unit = {
            if (elements.isNotEmpty()) load(Opcodes.ALOAD, "b")
            step(aload(0))
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, self, "canEqual", "(L$OBJECT;)Z"))
            jump(Opcodes.IFEQ, "false")
        }
        if (case.equalsMayOmitCanEqual) m.optional(canEqualCall) else m.canEqualCall()
        m.label("true")
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "end")
        m.label("false")
        m.step(Insn.IntConstant(0))
        m.label("end")
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /**
     * Scala 3.3.4's `equals`, read from `javap -c` over `Mixed`, `One`, `Priv`, `Empty`,
     * `Outer$Inner` and the final `FT`, `F1`, `FE`, `FinalOuter$FIn` and `FinalHolder$FObj`:
     *
     * ```
     * aload_0; aload_1; if_acmpeq TRUE
     * aload_1; astore a; aload a; instanceof X; ifeq NO
     * [aload a; checkcast X; invokevirtual <...>$$outer; aload_0; getfield $outer; if_acmpne NO]   (an inner class)
     * aload a; checkcast X; astore b
     * [aload b; astore c]                                                       (3.7.3 to 3.8.3)
     * with no elements:  aload c; aload_0; invokevirtual canEqual; goto TEST
     *                    (or iconst_1; goto TEST, see [CaseClass.equalsMayOmitCanEqual])
     * otherwise:         <comparisons against c, failing to MISS>
     *                    aload c; aload_0; invokevirtual canEqual; ifeq MISS   (may be absent likewise)
     *                    iconst_1; goto JOIN; MISS: iconst_0; JOIN: goto TEST
     * NO: iconst_0; goto TEST
     * TEST: ifeq FALSE
     * TRUE: iconst_1; goto END
     * FALSE: iconst_0
     * END: ireturn
     * ```
     *
     * with `c` standing for `b` where the copy is absent. Scala 3.7.3 to 3.8.3 store the cast
     * instance a second time, `aload b; astore c`, and read every element through the copy.
     * Scala 3.3.8, 3.8.4 and later compare a null-safe reference element through
     * `java.util.Objects.equals` (see [compareElements]).
     */
    private fun matchesEqualsScala3(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = case.elements ?: return false
        val self = case.name
        val mayOmitCanEqual = case.equalsMayOmitCanEqual
        val m = Match(body)
        m.step(aload(0))
        m.step(aload(1))
        m.jump(Opcodes.IF_ACMPEQ, "true")
        m.step(aload(1))
        m.store(Opcodes.ASTORE, "a")
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
        m.jump(Opcodes.IFEQ, "no")
        m.optional {
            load(Opcodes.ALOAD, "a")
            step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
            take { insn -> (insn as? Insn.Call)?.takeIf { it.isOuterAccessor(self) } }
            outerReference(self, accessor = false)
            jump(Opcodes.IF_ACMPNE, "no")
        }
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
        m.store(Opcodes.ASTORE, "b")
        val copied =
            m.optional {
                load(Opcodes.ALOAD, "b")
                store(Opcodes.ASTORE, "c")
                true
            } == true
        val that = if (copied) "c" else "b"
        val canEqualCall: Match.() -> Unit = {
            load(Opcodes.ALOAD, that)
            step(aload(0))
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, self, "canEqual", "(L$OBJECT;)Z"))
        }
        if (elements.isEmpty()) {
            if (mayOmitCanEqual) m.either(canEqualCall) { step(Insn.IntConstant(1)) } else m.canEqualCall()
            m.jump(Opcodes.GOTO, "test")
        } else {
            m.compareElements(elements, that, "miss", objectsEquals = true)
            val canEqualTest: Match.() -> Unit = {
                canEqualCall()
                jump(Opcodes.IFEQ, "miss")
            }
            if (mayOmitCanEqual) m.optional(canEqualTest) else m.canEqualTest()
            m.step(Insn.IntConstant(1))
            m.jump(Opcodes.GOTO, "join")
            m.label("miss")
            m.step(Insn.IntConstant(0))
            m.label("join")
            m.jump(Opcodes.GOTO, "test")
        }
        m.label("no")
        m.step(Insn.IntConstant(0))
        m.jump(Opcodes.GOTO, "test")
        m.label("test")
        m.jump(Opcodes.IFEQ, "false")
        m.label("true")
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "end")
        m.label("false")
        m.step(Insn.IntConstant(0))
        m.label("end")
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /**
     * `productElement` ([names] false) or `productElementName` ([names] true), read from `javap
     * -c` over `Mixed`, `One`, `Cc`, `Round`, `Empty` and `Outer$Inner` in both versions:
     *
     * ```
     * iload_1; istore k
     * a tableswitch on k over 0 to <arity - 1>,         (every Scala 2.13.15 class, and Scala 3.3.4 with many elements)
     *   or per element i: <i>; iload k; if_icmpne NEXT  (Scala 3.3.4 with few elements)
     * per element, in order: its case
     * DEFAULT: out of range
     * [SHARED: box; areturn]
     * ```
     *
     * with no dispatch at all for a class with no elements. An element's case is `ldc "<name>";
     * areturn` for a name. For a value it is `aload_0` and a read of the element through its
     * accessor (Scala 2.13.15) or `_N` (Scala 3.3.4), then either its `BoxesRunTime.boxTo...` and
     * `areturn`, or `goto SHARED` when every element has one primitive type and Scala 3.3.4 boxes
     * once at the end (`Cc`). Out of range is `iload_1; Statics.ioobe(I)`, `checkcast String` for
     * a name, and `areturn` in Scala 2.13.15, and `new IndexOutOfBoundsException(n.toString)` and
     * `athrow` in Scala 3.3.4, which writes a second, unreachable `athrow` after it when a
     * `tableswitch` shares one box (`P3`, three `double` elements). Each part named for one
     * version above counts only in a class of that version.
     *
     * Two releases word the out-of-range case differently, read with `javap` over `Cc`, `P3` and
     * `Empty`: Scala 2.12.20 throws `new IndexOutOfBoundsException(Integer.toString(n))` (`new; dup;
     * iload_1; invokestatic Integer.toString; invokespecial <init>(String); athrow`) in place of
     * `Statics.ioobe`, and Scala 3.9.0 passes the index to `IndexOutOfBoundsException.<init>(int)`
     * with no string conversion (`new; dup; iload_1; invokespecial <init>(I)V; athrow`).
     */
    private fun matchesIndexed(
        case: CaseClass,
        body: List<Insn>,
        names: Boolean,
    ): Boolean {
        val elements = case.elements ?: return false
        val count = elements.size
        val scala3 = case.shape.isScala3
        return listOf(true, false).any { tableSwitch ->
            if ((count == 0 || !scala3) && !tableSwitch) return@any false
            val m = Match(body)
            m.step(Insn.Var(Opcodes.ILOAD, 1))
            m.store(Opcodes.ISTORE, "k")
            var shared: Type? = null
            val caseBody = { element: Element ->
                if (names) {
                    m.step(Insn.Constant(element.name))
                    m.step(areturn())
                } else {
                    m.step(aload(0))
                    val alias = "_${element.index + 1}"
                    when {
                        !scala3 -> m.step(element.read)

                        case.declares(
                            alias,
                            "()${element.type}",
                        ) -> m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, case.name, alias, "()${element.type}"))

                        else -> m.fail()
                    }
                    val boxed =
                        m.optional {
                            box(element.type)
                            step(areturn())
                            true
                        }
                    if (boxed == null) {
                        if (!scala3 || !element.isPrimitive || (shared != null && shared != element.type)) m.fail()
                        shared = element.type
                        m.jump(Opcodes.GOTO, "shared")
                    }
                }
            }
            if (count > 0 && tableSwitch) {
                m.load(Opcodes.ILOAD, "k")
                m.tableSwitch(count - 1, elements.map { "case${it.index}" }, "default")
                for (element in elements) {
                    m.label("case${element.index}")
                    caseBody(element)
                }
            } else {
                for (element in elements) {
                    m.step(Insn.IntConstant(element.index))
                    m.load(Opcodes.ILOAD, "k")
                    m.jump(Opcodes.IF_ICMPNE, if (element.index == count - 1) "default" else "test${element.index + 1}")
                    caseBody(element)
                    if (element.index < count - 1) m.label("test${element.index + 1}")
                }
            }
            m.label("default")
            if (scala3) {
                m.step(Insn.TypeOperand(Opcodes.NEW, OUT_OF_BOUNDS))
                m.step(Insn.Plain(Opcodes.DUP))
                m.step(Insn.Var(Opcodes.ILOAD, 1))
                m.either(
                    {
                        step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxToInteger", "(I)Ljava/lang/Integer;"))
                        step(Insn.Call(Opcodes.INVOKEVIRTUAL, INTEGER, "toString", "()L$STRING;"))
                        step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(L$STRING;)V"))
                    },
                    { step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(I)V")) },
                )
                m.step(Insn.Plain(Opcodes.ATHROW))
                if (shared != null) m.optional { step(Insn.Plain(Opcodes.ATHROW)) }
            } else {
                val ioobe: Match.() -> Unit = {
                    step(Insn.Var(Opcodes.ILOAD, 1))
                    step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "ioobe", "(I)L$OBJECT;"))
                    if (names) step(Insn.TypeOperand(Opcodes.CHECKCAST, STRING))
                    step(areturn())
                }
                // Scala 2.12's wording, in productElement only: 2.12 writes no productElementName.
                val scala212: Match.() -> Unit = {
                    step(Insn.TypeOperand(Opcodes.NEW, OUT_OF_BOUNDS))
                    step(Insn.Plain(Opcodes.DUP))
                    step(Insn.Var(Opcodes.ILOAD, 1))
                    step(Insn.Call(Opcodes.INVOKESTATIC, INTEGER, "toString", "(I)L$STRING;"))
                    step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(L$STRING;)V"))
                    step(Insn.Plain(Opcodes.ATHROW))
                }
                if (!names && case.mayBeScala212) m.either(ioobe, scala212) else m.ioobe()
            }
            shared?.let { type ->
                m.label("shared")
                m.box(type)
                m.step(areturn())
            }
            m.matched
        }
    }

    /**
     * The plumbing methods of the companion [companion] of the case class [partner]. Shapes, read
     * from `javap -c` over the companions of `Cc`, `One`, `Empty`, `Mixed`, `Priv`, `Hidden`,
     * `Outer$Inner` and the local `Local` in both versions:
     *
     * - `apply`, returning the partner: see [matchesConstruction], the outer reference read from
     *   the companion's own `$outer`.
     * - `unapply(<partner>)`: in Scala 3.3.4, `aload_1; areturn`, or `iconst_1; ireturn` returning
     *   `boolean` for a class with no elements. In Scala 2.13.15, see [matchesUnapplyScala2].
     * - `toString()`: `ldc "<partner's source name>"; areturn`.
     * - Scala 3's `fromProduct(scala.Product)`: `new <partner>; dup`, the outer reference, then
     *   per element `aload_1; <index>; invokeinterface Product.productElement(I)` and its
     *   `BoxesRunTime.unboxTo...`, or `checkcast` to its type unless that is `Object`, then
     *   `invokespecial <partner>.<init>; areturn`, the constructor being the partner's primary
     *   one. Scala 3.7.0 and later read the elements into locals before the `new` (see
     *   [matchesFromProduct]). Its bridge returning `Object` is `aload_0;
     *   aload_1; invokevirtual fromProduct; areturn`.
     *
     * The `unapply` and `fromProduct` shapes of one version count only in a companion of that
     * version (see [readShape]): a Scala 2 companion's hand-written `unapply` returning its
     * argument or a constant `true`, or its `fromProduct`, is the adopter's (`Id$`, `U0$`, `FP$`).
     *
     * A hand-written `apply(String)` in an explicit companion constructs its partner from other
     * values than its own parameters, and is not marked. scalac 2.13.15 and 3.3.4 give no
     * case-class companion a `readResolve`, so none is marked.
     */
    private fun companionPlumbing(
        companion: ClassShape,
        partner: CaseClass,
    ): Set<Pair<String, String>> {
        val partnerType = "L${partner.name};"
        val scala3 = companion.isScala3
        return companion
            .instanceMethods()
            .filter { (key, body) ->
                val (name, descriptor) = key
                when (name) {
                    "apply" -> {
                        Type.getReturnType(descriptor).descriptor == partnerType &&
                            matchesConstruction(
                                body,
                                partner.name,
                                descriptor,
                                partner.shape,
                                companion.internalName,
                                outerAccessor = false,
                            )
                    }

                    "unapply" -> {
                        when (descriptor) {
                            "($partnerType)$partnerType" -> {
                                scala3 && body == listOf(aload(1), areturn())
                            }

                            "($partnerType)Z" -> {
                                partner.elements?.isEmpty() == true &&
                                    if (scala3) {
                                        body == listOf(Insn.IntConstant(1), Insn.Plain(Opcodes.IRETURN))
                                    } else {
                                        matchesUnapplyScala2(partner, body)
                                    }
                            }

                            "($partnerType)Lscala/Option;" -> {
                                !scala3 && partner.elements?.isNotEmpty() == true && matchesUnapplyScala2(partner, body)
                            }

                            else -> {
                                false
                            }
                        }
                    }

                    "toString" -> {
                        descriptor == "()L$STRING;" && body == listOf(Insn.Constant(partner.shape.sourceName), areturn())
                    }

                    "fromProduct" -> {
                        scala3 &&
                            when (descriptor) {
                                "(L$PRODUCT;)$partnerType" -> {
                                    matchesFromProduct(companion, partner, body)
                                }

                                "(L$PRODUCT;)L$OBJECT;" -> {
                                    companion.methods["fromProduct" to "(L$PRODUCT;)$partnerType"]?.body?.let {
                                        matchesFromProduct(companion, partner, it)
                                    } == true &&
                                        body ==
                                        listOf(
                                            aload(0),
                                            aload(1),
                                            Insn.Call(
                                                Opcodes.INVOKEVIRTUAL,
                                                companion.internalName,
                                                "fromProduct",
                                                "(L$PRODUCT;)$partnerType",
                                            ),
                                            areturn(),
                                        )
                                }

                                else -> {
                                    false
                                }
                            }
                    }

                    else -> {
                        false
                    }
                }
            }.mapTo(mutableSetOf()) { it.first }
    }

    /**
     * Scala 2.13.15's `unapply`, read from `javap -c` over the companions of `Empty`, `One`,
     * `Outer$Inner`, `Cc`, `Box`, `Hidden`, `Priv` and `Mixed`:
     *
     * ```
     * aload_1; ifnonnull SOME
     * with no elements: iconst_0; ireturn; SOME: iconst_1; ireturn
     * otherwise:        getstatic scala/None$.MODULE$; areturn
     *                   SOME: new scala/Some; dup; <value>; invokespecial Some.<init>(Object); areturn
     * ```
     *
     * The value of one element is `aload_1`, its read and its box. Of several, it is `new
     * scala/TupleN; dup`, each element's `aload_1`, read and box, and `invokespecial
     * TupleN.<init>`, except that two elements both of type `int`, `long`, `double`, `char` or
     * `boolean` build the specialised `scala/Tuple2$mc<types>$sp` from unboxed values
     * (`Tuple2$mcII$sp` for `Cc`, `Tuple2$mcJZ$sp` for `Hidden`).
     */
    private fun matchesUnapplyScala2(
        partner: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = partner.elements ?: return false
        val reads = elements.map { it.readFromOutside ?: return false }
        val m = Match(body)
        m.step(aload(1))
        m.jump(Opcodes.IFNONNULL, "some")
        if (elements.isEmpty()) {
            m.step(Insn.IntConstant(0))
            m.step(Insn.Plain(Opcodes.IRETURN))
            m.label("some")
            m.step(Insn.IntConstant(1))
            m.step(Insn.Plain(Opcodes.IRETURN))
            return m.matched
        }
        if (elements.size > 22) return false
        m.step(Insn.Field(Opcodes.GETSTATIC, "scala/None$", MODULE_FIELD, "Lscala/None$;"))
        m.step(areturn())
        m.label("some")
        m.step(Insn.TypeOperand(Opcodes.NEW, "scala/Some"))
        m.step(Insn.Plain(Opcodes.DUP))
        val specialised = elements.size == 2 && elements.all { it.type.descriptor in TUPLE2_SPECIALISED }
        if (elements.size == 1) {
            m.step(aload(1))
            m.step(reads.single())
            m.box(elements.single().type)
        } else {
            val tuple =
                if (specialised) {
                    "scala/Tuple2\$mc${elements.joinToString(
                        "",
                    ) { it.type.descriptor }}\$sp"
                } else {
                    "scala/Tuple${elements.size}"
                }
            m.step(Insn.TypeOperand(Opcodes.NEW, tuple))
            m.step(Insn.Plain(Opcodes.DUP))
            for ((element, read) in elements.zip(reads)) {
                m.step(aload(1))
                m.step(read)
                if (!specialised) m.box(element.type)
            }
            val constructor =
                if (specialised) "(${elements.joinToString("") { it.type.descriptor }})V" else "(${"L$OBJECT;".repeat(elements.size)})V"
            m.step(Insn.Call(Opcodes.INVOKESPECIAL, tuple, "<init>", constructor))
        }
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, "scala/Some", "<init>", "(L$OBJECT;)V"))
        m.step(areturn())
        return m.matched
    }

    /**
     * Scala 3's `fromProduct`; see [companionPlumbing]. Scala 3.3.4 builds the instance in one
     * expression, the `new` before the element reads (`javap` over `Cc`, `Mixed`, `Outer$Inner`):
     * `new; dup; [outer]; per element: aload_1; <index>; invokeinterface productElement; <unbox or
     * checkcast>; invokespecial <init>`. Scala 3.7.0 and later read every element into a local first
     * and build the instance after, the locals taking slots from 2 in element order, a `long` or
     * `double` two wide: `per element: aload_1; <index>; invokeinterface productElement; <unbox or
     * checkcast>; <store>; then new; dup; [outer]; per element: <load>; invokespecial <init>`.
     */
    private fun matchesFromProduct(
        companion: ClassShape,
        partner: CaseClass,
        body: List<Insn>,
    ): Boolean = listOf(false, true).any { viaLocals -> matchesFromProduct(companion, partner, body, viaLocals) }

    private fun matchesFromProduct(
        companion: ClassShape,
        partner: CaseClass,
        body: List<Insn>,
        viaLocals: Boolean,
    ): Boolean {
        val elements = partner.elements ?: return false
        val m = Match(body)
        var outer: String? = null
        if (!viaLocals) {
            m.step(Insn.TypeOperand(Opcodes.NEW, partner.name))
            m.step(Insn.Plain(Opcodes.DUP))
            outer = m.optional { outerReference(companion.internalName, accessor = false) }
        }
        var slot = 2
        for (element in elements) {
            m.step(aload(1))
            m.step(Insn.IntConstant(element.index))
            m.step(Insn.Call(Opcodes.INVOKEINTERFACE, PRODUCT, "productElement", "(I)L$OBJECT;"))
            when (element.type.sort) {
                in Type.BOOLEAN..Type.DOUBLE -> {
                    m.step(unbox(element.type))
                }

                Type.OBJECT, Type.ARRAY -> {
                    if (element.type.internalName !=
                        OBJECT
                    ) {
                        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, element.type.internalName))
                    }
                }

                else -> {
                    m.fail()
                }
            }
            if (viaLocals) {
                m.step(Insn.Var(element.type.getOpcode(Opcodes.ISTORE), slot))
                slot += element.type.size
            }
        }
        if (viaLocals) {
            m.step(Insn.TypeOperand(Opcodes.NEW, partner.name))
            m.step(Insn.Plain(Opcodes.DUP))
            outer = m.optional { outerReference(companion.internalName, accessor = false) }
            slot = 2
            for (element in elements) {
                m.step(Insn.Var(element.type.getOpcode(Opcodes.ILOAD), slot))
                slot += element.type.size
            }
        }
        val constructor = "(${outer.orEmpty()}${elements.joinToString("") { it.type.descriptor }})V"
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, partner.name, "<init>", constructor))
        m.step(areturn())
        return m.matched && partner.shape.isPrimaryConstructor(constructor)
    }

    /** `BoxesRunTime.boxTo...` for a primitive [type]; nothing for a reference. */
    private fun Match.box(type: Type) {
        val boxed = boxedName(type) ?: return
        step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxTo${boxed.substringAfterLast('/')}", "($type)L$boxed;"))
    }

    private fun unbox(type: Type): Insn {
        val name =
            when (type.sort) {
                Type.INT -> "Int"
                Type.CHAR -> "Char"
                else -> boxedName(type)!!.substringAfterLast('/')
            }
        return Insn.Call(Opcodes.INVOKESTATIC, BOXES, "unboxTo$name", "(L$OBJECT;)$type")
    }

    private fun boxedName(type: Type): String? =
        when (type.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> null
        }

    private fun parameterLoads(
        descriptor: String,
        firstSlot: Int,
    ): List<Insn> {
        var slot = firstSlot
        return Type.getArgumentTypes(descriptor).map { type -> Insn.Var(type.getOpcode(Opcodes.ILOAD), slot).also { slot += type.size } }
    }

    /** The parameter types of [descriptor], without the parentheses. */
    private fun parameterPart(descriptor: String): String = descriptor.substring(1, descriptor.lastIndexOf(')'))

    private fun aload(slot: Int): Insn = Insn.Var(Opcodes.ALOAD, slot)

    private fun areturn(): Insn = Insn.Plain(Opcodes.ARETURN)

    /**
     * One bytecode instruction, as the matchers compare it. `iconst`, `bipush`, `sipush` and an
     * `ldc` of an `int` are all an [IntConstant]. A jump or switch names its targets by the index
     * of the instruction they land on.
     */
    private sealed interface Insn {
        data class Plain(
            val opcode: Int,
        ) : Insn

        data class IntConstant(
            val value: Int,
        ) : Insn

        data class Var(
            val opcode: Int,
            val slot: Int,
        ) : Insn

        data class Field(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class Call(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class TypeOperand(
            val opcode: Int,
            val type: String,
        ) : Insn

        data class Constant(
            val value: Any?,
        ) : Insn

        data class Jump(
            val opcode: Int,
            val target: Int,
        ) : Insn

        data class TableSwitch(
            val min: Int,
            val max: Int,
            val default: Int,
            val targets: List<Int>,
        ) : Insn

        data class LookupSwitch(
            val keys: List<Int>,
            val default: Int,
            val targets: List<Int>,
        ) : Insn

        data class Dynamic(
            val name: String,
            val descriptor: String,
            val bootstrap: Handle,
            val arguments: List<Any?>,
        ) : Insn

        /** Any instruction no shape here contains, such as `iinc`. */
        data object Other : Insn
    }

    /**
     * Records a method's instructions as [Insn]s and hands them to [onEnd], null for a method
     * with no code, with whether it has a try-catch block. Labels, line numbers, frames and
     * local-variable entries are not instructions; a label only gives a jump its target index.
     */
    private class BodyRecorder(
        private val onEnd: (code: List<Insn>?, hasHandler: Boolean, handlers: List<Handler>) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val raw = mutableListOf<Any>()
        private val labels = HashMap<Label, Int>()
        private var hasCode = false
        private var hasHandler = false
        private val tryCatchBlocks = mutableListOf<PendingHandler>()

        private class PendingHandler(
            val start: Label,
            val end: Label,
            val target: Label,
            val type: String?,
        )

        private class PendingLookup(
            val keys: List<Int>,
            val default: Label,
            val targets: List<Label>,
        )

        private class PendingJump(
            val opcode: Int,
            val target: Label,
        )

        private class PendingSwitch(
            val min: Int,
            val max: Int,
            val default: Label,
            val targets: List<Label>,
        )

        private fun add(insn: Any) {
            raw += insn
        }

        override fun visitCode() {
            hasCode = true
        }

        override fun visitLabel(label: Label) {
            labels[label] = raw.size
        }

        override fun visitInsn(opcode: Int) =
            add(if (opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5) Insn.IntConstant(opcode - Opcodes.ICONST_0) else Insn.Plain(opcode))

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = add(if (opcode == Opcodes.NEWARRAY) Insn.Other else Insn.IntConstant(operand))

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) = add(Insn.Var(opcode, varIndex))

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = add(Insn.Field(opcode, owner, name, descriptor))

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) = add(Insn.Call(opcode, owner, name, descriptor))

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = add(Insn.TypeOperand(opcode, type))

        override fun visitLdcInsn(value: Any?) = add(if (value is Int) Insn.IntConstant(value) else Insn.Constant(value))

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = add(PendingJump(opcode, label))

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = add(PendingSwitch(min, max, dflt, labels.toList()))

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = add(Insn.Other)

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) = add(Insn.Dynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments.toList()))

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = add(PendingLookup(keys.toList(), dflt, labels.toList()))

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = add(Insn.Other)

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            hasHandler = true
            tryCatchBlocks += PendingHandler(start, end, handler, type)
        }

        override fun visitEnd() {
            if (!hasCode) return onEnd(null, hasHandler, emptyList())
            onEnd(
                raw.map { insn ->
                    when (insn) {
                        is PendingJump -> {
                            Insn.Jump(insn.opcode, labels.getValue(insn.target))
                        }

                        is PendingLookup -> {
                            Insn.LookupSwitch(insn.keys, labels.getValue(insn.default), insn.targets.map(labels::getValue))
                        }

                        is PendingSwitch -> {
                            Insn.TableSwitch(
                                insn.min,
                                insn.max,
                                labels.getValue(insn.default),
                                insn.targets.map(labels::getValue),
                            )
                        }

                        else -> {
                            insn as Insn
                        }
                    }
                },
                hasHandler,
                tryCatchBlocks.map { Handler(labels.getValue(it.start), labels.getValue(it.end), labels.getValue(it.target), it.type) },
            )
        }
    }

    /**
     * Walks a body once, front to back, against an expected shape. Each step consumes one
     * instruction or fails the match; nothing is skipped, so an instruction the shape does not
     * name fails it. A jump binds its label name to its target index the first time the name is
     * seen, and [label] checks that the walk has reached that index; a local-variable slot is
     * bound by name the same way. [optional] and [either] try a part of the shape and roll back
     * when it fails.
     */
    private class Match(
        private val insns: List<Insn>,
        private val firstLocal: Int = 2,
    ) {
        private var pos = 0
        private var ok = true
        private var labels = HashMap<String, Int>()
        private var slots = HashMap<String, Int>()

        val matched: Boolean get() = ok && pos == insns.size

        fun fail() {
            ok = false
        }

        fun step(expected: Insn) {
            if (ok && pos < insns.size && insns[pos] == expected) pos++ else ok = false
        }

        fun <T : Any> take(capture: (Insn) -> T?): T? {
            if (!ok || pos >= insns.size) return null.also { ok = false }
            return capture(insns[pos])?.also { pos++ } ?: null.also { ok = false }
        }

        private fun bind(
            names: HashMap<String, Int>,
            name: String,
            value: Int,
        ): Boolean = names.getOrPut(name) { value } == value

        fun label(name: String) {
            if (ok && !bind(labels, name, pos)) ok = false
        }

        fun jump(
            opcode: Int,
            label: String,
        ) {
            take { insn -> (insn as? Insn.Jump)?.takeIf { it.opcode == opcode && bind(labels, label, it.target) } }
        }

        fun tableSwitch(
            max: Int,
            cases: List<String>,
            default: String,
        ) {
            take { insn ->
                (insn as? Insn.TableSwitch)?.takeIf { switch ->
                    switch.min == 0 &&
                        switch.max == max &&
                        bind(labels, default, switch.default) &&
                        cases.zip(switch.targets).all { (name, target) -> bind(labels, name, target) }
                }
            }
        }

        /**
         * A switch on [keys], sorted ascending, either kind scalac lowers to: a `lookupswitch` on
         * exactly [keys], or a `tableswitch` from the first key to the last whose gaps go to
         * [default]. Key i jumps to [cases]`[i]`.
         */
        fun keyedSwitch(
            keys: List<Int>,
            cases: List<String>,
            default: String,
        ) {
            take { insn ->
                when (insn) {
                    is Insn.LookupSwitch -> {
                        insn.takeIf { switch ->
                            switch.keys == keys &&
                                bind(labels, default, switch.default) &&
                                cases.zip(switch.targets).all { (name, target) -> bind(labels, name, target) }
                        }
                    }

                    is Insn.TableSwitch -> {
                        insn.takeIf { switch ->
                            keys.isNotEmpty() &&
                                switch.min == keys.first() &&
                                switch.max == keys.last() &&
                                bind(labels, default, switch.default) &&
                                (switch.min..switch.max).withIndex().all { (index, key) ->
                                    val case = keys.indexOf(key)
                                    val target = switch.targets[index]
                                    if (case < 0) target == switch.default else bind(labels, cases[case], target)
                                }
                        }
                    }

                    else -> {
                        null
                    }
                }
            }
        }

        /** The instruction index [name] is bound to, null while it is unbound. */
        fun labelIndex(name: String): Int? = labels[name]

        /** A load of the slot bound to [name]. */
        fun load(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && slots[name] == it.slot } }
        }

        /** A store to a slot at or after [firstLocal], the first slot past `this` and the parameters, bound to [name]. */
        fun store(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && it.slot >= firstLocal && bind(slots, name, it.slot) } }
        }

        /** Runs [part]; when it fails, rolls back to where it started and returns null. */
        fun <T> optional(part: Match.() -> T): T? {
            if (!ok) return null
            val start = pos
            val savedLabels = HashMap(labels)
            val savedSlots = HashMap(slots)
            val result = part()
            if (ok) return result
            pos = start
            labels = savedLabels
            slots = savedSlots
            ok = true
            return null
        }

        /** Runs [first], or [second] when [first] fails. */
        fun either(
            first: Match.() -> Unit,
            second: Match.() -> Unit,
        ) {
            if (optional { first().let { true } } == null) second()
        }
    }
}
