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

internal object ScalaGeneratedMethods {
    private const val MODULE_FIELD = "MODULE\$"

    private const val OLD_NO_CASE_RECIPE = "enum case not found: \u0001"
    private const val OUTER_FIELD = "\$outer"
    private const val OBJECT = "java/lang/Object"
    private const val JAVA_ENUM = "java/lang/Enum"
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
    private const val LAZY_REF = "scala/runtime/LazyRef"
    private const val STRING_CONCAT = "java/lang/invoke/StringConcatFactory"
    private const val THROWABLE = "java/lang/Throwable"
    private const val VALUES_FIELD = "\$values"
    private const val CONCAT_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;"
    private const val EXTENSION = "\$extension"
    private const val NONE_OBJECT = "scala/None\$"
    private const val SOME = "scala/Some"
    private const val OPTION_DESCRIPTOR = "Lscala/Option;"

    private const val PLAIN_CASE_FIELD_FLAGS = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    private const val INSTANCE_CASE_FIELD_FLAGS = Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL

    private const val FIELD_KIND_FLAGS =
        Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL

    private const val ENUM_CASE_ACCESSOR_FLAG = 0x4000

    private const val HASH_SEED = -889275714

    private val ELEMENT_ALIAS = Regex("_([1-9]\\d*)")

    private val ACCESS_ACCESSOR = Regex("(.+)\\\$access\\\$(\\d+)")

    private val CASE_CLASS_SIGNATURE =
        setOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productArity" to "()I",
            "productElement" to "(I)Ljava/lang/Object;",
            "productPrefix" to "()Ljava/lang/String;",
        )

    private val COMPANION_METHODS = setOf("apply", "unapply", "toString", "fromProduct")

    private val TUPLE2_SPECIALISED = setOf("I", "J", "D", "C", "Z")

    private val FROM_ORDINAL_DESCRIPTOR = Regex("\\(I\\)L[^;\\[]+;")

    private val VALUE_OF_DESCRIPTOR = Regex("\\(L$STRING;\\)L[^;\\[]+;")

    private val WRITE_REPLACE = "writeReplace" to "()Ljava/lang/Object;"
    private val ORDINAL = "ordinal" to "()I"
    private val READ_RESOLVE = "readResolve" to "()Ljava/lang/Object;"
    private val PRODUCT_ELEMENT_NAME = "productElementName" to "(I)Ljava/lang/String;"

    fun of(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): Map<Pair<String, String>, GeneratedBy> = marksOf(readShape(classBytes), readOnce(lookup)).generated

    fun forwarders(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): Forwarders {
        val shape = readShape(classBytes)
        val marks = forwarderMarks(shape, readOnce(lookup))
        return Forwarders(marks.generated, marks.passThrough)
    }

    class Forwarders(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val passThrough: Set<Pair<String, String>>,
    )

    class Result(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val unread: Map<Pair<String, String>, UnreadShape>,
        val unreadRelease: String?,
        val cause: UnreadCause? = null,
        val passThrough: Set<Pair<String, String>> = emptySet(),
    )

    fun analyse(
        classBytes: ByteArray,
        reader: (internalName: String) -> ByteArray?,
        releaseOf: (internalName: String) -> String?,
    ): Result {
        val lookup = readOnce(reader)
        val shape = readShape(classBytes)
        val sides = valueSidesOf(shape, lookup)
        val marks = marksOf(shape, lookup, sides)
        val generated = marks.generated
        val base = outlineOf(shape, lookup, generated, sides)
        val forwarders = mayHaveForwarders(shape, sides, generated)
        val undecided = marks.undecided.filter { it !in generated }
        if (base.isEmpty() && !forwarders && undecided.isEmpty()) {
            return Result(generated, emptyMap(), null, passThrough = marks.passThrough)
        }

        fun withForwarders(): Map<Pair<String, String>, UnreadShape> {
            val outlined = if (forwarders) forwarderOutlineOf(shape, lookup, generated, sides) + base else base
            return outlined + undecided.filter { it !in outlined }.associateWith { UnreadShape.TRAIT_FORWARDER }
        }

        if (!shape.isScala3) {
            val candidates = withForwarders()
            return Result(generated, candidates, null, UnreadCause.VERSION_BLIND.takeIf { candidates.isNotEmpty() }, marks.passThrough)
        }
        val release = releaseOf(shape.internalName)
        return when {
            release == null -> {
                val candidates = withForwarders()
                Result(generated, candidates, null, UnreadCause.VERSION_BLIND.takeIf { candidates.isNotEmpty() }, marks.passThrough)
            }

            ScalaReleases.isRead(release) -> {
                val enumCase = shape.isEnumCaseClass(lookup)
                val pairing = if (enumCase) null else pairEnumCompanion(shape, lookup)
                val accessors = pairing?.let { enumCaseAccessorKeys(shape, it.enumClass, lookup) }.orEmpty()
                val outlined =
                    (if (enumCase) withForwarders() else base).filterKeys {
                        enumCase || (pairing != null && (isRefusedInEnumCompanion(it) || it in accessors))
                    }
                val held = outlined + undecided.filter { it !in outlined }.associateWith { UnreadShape.TRAIT_FORWARDER }
                Result(generated, held, null, UnreadCause.UNREAD_STRUCTURE.takeIf { held.isNotEmpty() }, marks.passThrough)
            }

            else -> {
                val candidates = withForwarders()
                if (candidates.isEmpty()) {
                    Result(generated, candidates, null, passThrough = marks.passThrough)
                } else {
                    Result(generated, candidates, release, UnreadCause.UNREAD_RELEASE, marks.passThrough)
                }
            }
        }
    }

    private fun ClassShape.isEnumCaseClass(lookup: (String) -> ByteArray?): Boolean {
        val parent = superName?.takeIf { it != OBJECT } ?: return false
        return try {
            val header = lookup(parent)?.let(::ClassReader) ?: return false
            isEnumClass(header.access, header.interfaces.toList())
        } catch (_: Exception) {
            false
        }
    }

    private fun isRefusedInEnumCompanion(key: Pair<String, String>): Boolean {
        val (name, descriptor) = key
        return name in REFUSED_COMPANION_METHODS || (name == "ordinal" && descriptor == "(L$OBJECT;)I")
    }

    private fun pairEnumCompanion(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): EnumPairing? {
        if (!shape.isScala || !shape.isScala3 || !shape.internalName.endsWith("$") || MIRROR_SUM !in shape.interfaces) return null
        readClass(shape.internalName.removeSuffix("$"), lookup)?.takeIf { it.isEnumClass() }?.let { return EnumPairing(it, true, false) }
        if (shape.hasOwnModuleField) return null
        val fromOrdinal =
            shape.methods.filter { (key, method) ->
                key.first == "fromOrdinal" &&
                    FROM_ORDINAL_DESCRIPTOR.matches(key.second) &&
                    method.access and Opcodes.ACC_BRIDGE == 0
            }
        val enumName = fromOrdinal.keys.singleOrNull()?.let { Type.getReturnType(it.second).internalName } ?: return null
        val enumClass = readClass(enumName, lookup)?.takeIf { it.isEnumClass() } ?: return null
        val enclosing = shape.enclosingMethod ?: return null
        if (enumClass.enclosingMethod != enclosing || shape.innerClasses.none { it.name == enumName }) return null
        var unreadable = false
        for (entry in shape.innerClasses.distinctBy { it.name }) {
            val byOuter = entry.outerName == shape.internalName
            if (entry.name == shape.internalName || entry.name == enumName || !(byOuter || entry.outerName == null)) continue
            val listed = readClass(entry.name, lookup, bodiesOf = emptySet())
            if (listed == null) {
                unreadable = true
            } else if (listed.superName == enumName && (byOuter || listed.enclosingMethod?.owner == shape.internalName)) {
                return EnumPairing(enumClass, true, true)
            }
        }
        return if (unreadable) EnumPairing(enumClass, false, true) else null
    }

    private class EnumPairing(
        val enumClass: ClassShape,
        val certain: Boolean,
        val local: Boolean,
    )

    private val REFUSED_COMPANION_METHODS = setOf("values", "valueOf", "fromOrdinal", "\$new")

    private val ClassShape.isSerializable: Boolean
        get() = "java/io/Serializable" in interfaces || "scala/Serializable" in interfaces

    private class ValueClass(
        val shape: ClassShape,
        val companion: ClassShape,
        val field: FieldShape,
    ) {
        val name: String get() = shape.internalName
        val moduleName: String get() = companion.internalName
        val type: Type = Type.getType(field.descriptor)
        val isCase: Boolean = CASE_CLASS_SIGNATURE.all { shape.methods[it]?.isStatic == false }

        val mayBeScala212: Boolean get() = !shape.isScala3 && PRODUCT_ELEMENT_NAME !in shape.methods

        val module: Insn = Insn.Field(Opcodes.GETSTATIC, moduleName, MODULE_FIELD, "L$moduleName;")

        val reads: List<Insn> =
            listOf(Insn.Field(Opcodes.GETFIELD, shape.internalName, field.name, field.descriptor)) +
                shape.methods
                    .filter { (key, method) ->
                        !method.isStatic &&
                            key.second == "()${field.descriptor}" &&
                            method.body ==
                            listOf(
                                aload(0),
                                Insn.Field(Opcodes.GETFIELD, shape.internalName, field.name, field.descriptor),
                                Insn.Plain(type.getOpcode(Opcodes.IRETURN)),
                            )
                    }.keys
                    .flatMap { (accessor, descriptor) ->
                        listOf(
                            Insn.Call(Opcodes.INVOKEVIRTUAL, shape.internalName, accessor, descriptor),
                            Insn.Call(Opcodes.INVOKESPECIAL, shape.internalName, accessor, descriptor),
                        )
                    }

        fun load(slot: Int): Insn = Insn.Var(type.getOpcode(Opcodes.ILOAD), slot)

        fun back(): Insn = Insn.Plain(type.getOpcode(Opcodes.IRETURN))

        val boxedType: String get() = boxedName(type) ?: type.internalName
    }

    private class ValueSides(
        val boxed: ValueClass?,
        val companion: ValueClass?,
    )

    private fun valueSidesOf(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): ValueSides = ValueSides(valueClassOf(shape, lookup), valueClassCompanionOf(shape, lookup))

    private fun mayHaveForwarders(
        shape: ClassShape,
        sides: ValueSides,
        generated: Map<Pair<String, String>, GeneratedBy>,
    ): Boolean =
        sides.boxed != null ||
            sides.companion != null ||
            if (shape.access and Opcodes.ACC_INTERFACE != 0) {
                shape.methods.any { (key, method) -> key !in generated && method.isStatic && key.first.endsWith("$") }
            } else {
                shape.interfaces.isNotEmpty() &&
                    shape.methods.any { (key, method) ->
                        key !in generated &&
                            !method.isStatic &&
                            method.code.orEmpty().any {
                                it is Insn.Call && (
                                    (it.opcode == Opcodes.INVOKESTATIC && it.name == key.first + "$") ||
                                        callsJavaDefault(shape, key, it) ||
                                        callsSpecialisedTwin(shape, key, it)
                                )
                            }
                    }
            }

    private fun valueClassOf(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): ValueClass? {
        if (!shape.isScala || !shape.isFinal || shape.internalName.endsWith("$")) return null
        val module = "${shape.internalName}$"
        val first = Insn.Field(Opcodes.GETSTATIC, module, MODULE_FIELD, "L$module;")
        if (shape.methods.none { (_, method) -> !method.isStatic && method.body?.firstOrNull() == first }) return null
        return readClass(module, lookup)?.let { valueClassFrom(shape, it) }
    }

    private fun valueClassCompanionOf(
        companion: ClassShape,
        lookup: (String) -> ByteArray?,
    ): ValueClass? {
        if (!companion.isModuleClass ||
            companion.methods.none { (key, method) -> !method.isStatic && key.first.endsWith(EXTENSION) }
        ) {
            return null
        }
        return readClass(companion.internalName.removeSuffix("$"), lookup)?.let { valueClassFrom(it, companion) }
    }

    private fun valueClassFrom(
        shape: ClassShape,
        companion: ClassShape,
    ): ValueClass? {
        if (!shape.isScala || !shape.isFinal || shape.superName != OBJECT) return null
        val field = shape.fields.filter { it.access and Opcodes.ACC_STATIC == 0 }.singleOrNull() ?: return null
        val hasExtension = companion.methods.any { (key, method) -> !method.isStatic && key.first.endsWith(EXTENSION) }
        return if (companion.isModuleClass && hasExtension) ValueClass(shape, companion, field) else null
    }

    private fun forwarderMarks(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
        sides: ValueSides = valueSidesOf(shape, lookup),
    ): Marks {
        val generated = LinkedHashMap<Pair<String, String>, GeneratedBy>()
        val passThrough = LinkedHashSet<Pair<String, String>>()
        sides.boxed?.let { valueClass ->
            for ((key, mark) in valueClassForwarders(valueClass)) {
                generated[key] = mark
                passThrough += key
            }
        }
        val trait = traitForwarders(shape, lookup)
        for (key in trait.marked) generated.putIfAbsent(key, GeneratedBy.TRAIT_FORWARDER)
        return Marks(generated, passThrough, trait.undecided)
    }

    private val DEFAULT_GETTER_NAME = Regex(".*\\\$default\\\$\\d+")

    private fun valueClassForwarders(valueClass: ValueClass): Map<Pair<String, String>, GeneratedBy> {
        val result = LinkedHashMap<Pair<String, String>, GeneratedBy>()
        for ((key, method) in valueClass.shape.methods) {
            val (name, descriptor) = key
            if (method.isStatic || name == "<init>" || name == "<clinit>") continue
            val body = method.body ?: continue
            val targetDescriptor = "(${valueClass.type}${parameterPart(descriptor)})${Type.getReturnType(descriptor)}"
            val call = body.getOrNull(body.size - 2) as? Insn.Call ?: continue
            if (call.opcode != Opcodes.INVOKEVIRTUAL || call.owner != valueClass.moduleName || call.descriptor != targetDescriptor) continue
            val target = call.name
            if (!isExtensionOf(name, target) || valueClass.companion.methods[target to targetDescriptor]?.isStatic != false) continue
            val tail =
                parameterLoads(descriptor, 1) +
                    call +
                    Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))
            if (valueClass.reads.none { read -> body == listOf(valueClass.module, aload(0), read) + tail }) continue
            result[key] = if (valueClass.isCase && isCasePlumbing(valueClass, key)) GeneratedBy.CASE_CLASS else GeneratedBy.VALUE_CLASS
        }
        return result
    }

    private fun isExtensionOf(
        name: String,
        target: String,
    ): Boolean = target.startsWith(name + EXTENSION) && target.drop(name.length + EXTENSION.length).all(Char::isDigit)

    private fun isCasePlumbing(
        valueClass: ValueClass,
        key: Pair<String, String>,
    ): Boolean {
        val (name, descriptor) = key
        val element = valueClass.type.descriptor
        val string = "L$STRING;"
        return when (name) {
            "canEqual" -> descriptor == "(L$OBJECT;)Z"
            "equals" -> descriptor == "(L$OBJECT;)Z"
            "hashCode", "productArity" -> descriptor == "()I"
            "toString", "productPrefix" -> descriptor == "()$string"
            "productElement" -> descriptor == "(I)L$OBJECT;"
            "productElementName" -> descriptor == "(I)$string"
            "productIterator", "productElementNames" -> descriptor == "()$ITERATOR"
            "copy" -> descriptor == "($element)$element"
            else -> descriptor == "()$element" && (name == "_1" || name == "copy\$default\$1")
        }
    }

    private fun valueClassCompanionMarks(valueClass: ValueClass): Map<Pair<String, String>, GeneratedBy> {
        val element = valueClass.type.descriptor
        val mark = if (valueClass.isCase) GeneratedBy.CASE_CLASS else GeneratedBy.VALUE_CLASS
        val result = LinkedHashMap<Pair<String, String>, GeneratedBy>()
        for ((key, method) in valueClass.companion.methods) {
            if (method.isStatic) continue
            val body = method.body ?: continue
            val (name, descriptor) = key
            val matched =
                when {
                    name == "hashCode$EXTENSION" && descriptor == "($element)I" -> matchesHashCodeExtension(valueClass, body)
                    name == "equals$EXTENSION" && descriptor == "(${element}L$OBJECT;)Z" -> matchesEqualsExtension(valueClass, body)
                    valueClass.isCase -> matchesCaseExtension(valueClass, name, descriptor, body)
                    else -> false
                }
            if (matched) result[key] = mark
        }
        return result
    }

    private fun matchesHashCodeExtension(
        valueClass: ValueClass,
        body: List<Insn>,
    ): Boolean {
        val type = valueClass.type
        val load = valueClass.load(1)
        val done = Insn.Plain(Opcodes.IRETURN)
        val hashCode = "hashCode"
        val alternatives = mutableListOf<List<Insn>>()
        val boxed = boxedName(type)
        if (boxed == null) {
            val owners = if (type.sort == Type.OBJECT) listOf(type.internalName, OBJECT) else listOf(OBJECT)
            for (owner in owners) alternatives += listOf(load, Insn.Call(Opcodes.INVOKEVIRTUAL, owner, hashCode, "()I"), done)
            alternatives += listOf(load, Insn.Call(Opcodes.INVOKESTATIC, OBJECTS, hashCode, "(L$OBJECT;)I"), done)
        } else {
            alternatives += listOf(load, Insn.Call(Opcodes.INVOKESTATIC, boxed, hashCode, "($type)I"), done)
            alternatives +=
                listOf(
                    load,
                    Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxTo${boxed.substringAfterLast('/')}", "($type)L$boxed;"),
                    Insn.Call(Opcodes.INVOKEVIRTUAL, boxed, hashCode, "()I"),
                    done,
                )
            when (type.sort) {
                Type.INT, Type.SHORT, Type.BYTE, Type.CHAR -> {
                    alternatives += listOf(load, done)
                }

                Type.BOOLEAN -> {
                    alternatives +=
                        listOf(load, Insn.Jump(Opcodes.IFEQ, 4), Insn.IntConstant(1231), done, Insn.IntConstant(1237), done)
                }
            }
        }
        return body in alternatives
    }

    private fun matchesEqualsExtension(
        valueClass: ValueClass,
        body: List<Insn>,
    ): Boolean {
        val type = valueClass.type
        val other = 1 + type.size
        val m = Match(body, firstLocal = other + 1)
        val scala3 = valueClass.companion.isScala3
        val store = type.getOpcode(Opcodes.ISTORE)
        val load = type.getOpcode(Opcodes.ILOAD)
        val self = valueClass.name
        if (scala3) {
            m.step(aload(other))
            m.store(Opcodes.ASTORE, "a")
            m.load(Opcodes.ALOAD, "a")
            m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
            m.jump(Opcodes.IFEQ, "no")
            m.load(Opcodes.ALOAD, "a")
            m.jump(Opcodes.IFNONNULL, "some")
            m.step(Insn.Plain(Opcodes.ACONST_NULL))
            when (type.sort) {
                in Type.BOOLEAN..Type.DOUBLE -> m.step(unbox(type))
                Type.ARRAY -> m.step(Insn.TypeOperand(Opcodes.CHECKCAST, type.internalName))
            }
            m.jump(Opcodes.GOTO, "join")
            m.label("some")
            m.load(Opcodes.ALOAD, "a")
            m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
            m.take { insn -> insn.takeIf { it in valueClass.reads } }
            m.label("join")
            m.store(store, "v")
            val copied =
                m.optional {
                    load(load, "v")
                    store(store, "c")
                    true
                } == true
            m.step(valueClass.load(1))
            m.load(load, if (copied) "c" else "v")
            m.compareValues(type, "miss", objectsEquals = true)
            m.step(Insn.IntConstant(1))
            m.jump(Opcodes.GOTO, "end")
            m.label("miss")
            m.step(Insn.IntConstant(0))
            m.label("end")
            m.step(Insn.Plain(Opcodes.IRETURN))
            m.label("no")
            m.step(Insn.IntConstant(0))
            m.step(Insn.Plain(Opcodes.IRETURN))
        } else {
            m.step(aload(other))
            m.store(Opcodes.ASTORE, "a")
            m.load(Opcodes.ALOAD, "a")
            m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
            m.jump(Opcodes.IFEQ, "no")
            m.step(Insn.IntConstant(1))
            m.jump(Opcodes.GOTO, "test")
            m.label("no")
            m.jump(Opcodes.GOTO, "no2")
            m.label("no2")
            m.step(Insn.IntConstant(0))
            m.jump(Opcodes.GOTO, "test")
            m.label("test")
            m.jump(Opcodes.IFEQ, "false")
            m.step(aload(other))
            val nullChecked = type.sort == Type.OBJECT || type.sort == Type.ARRAY
            if (nullChecked) {
                m.jump(Opcodes.IFNONNULL, "some")
                m.step(Insn.Plain(Opcodes.ACONST_NULL))
                m.jump(Opcodes.GOTO, "join")
                m.label("some")
                m.step(aload(other))
            }
            m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
            m.take { insn -> insn.takeIf { it in valueClass.reads } }
            if (nullChecked) m.label("join")
            m.store(store, "v")
            m.step(valueClass.load(1))
            m.load(load, "v")
            m.compareValues(type, "false", objectsEquals = false)
            m.step(Insn.IntConstant(1))
            m.jump(Opcodes.GOTO, "end")
            m.label("false")
            m.step(Insn.IntConstant(0))
            m.label("end")
            m.step(Insn.Plain(Opcodes.IRETURN))
        }
        return m.matched
    }

    private fun Match.compareValues(
        type: Type,
        fail: String,
        objectsEquals: Boolean,
    ) {
        when (type.sort) {
            Type.INT, Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT -> {
                jump(Opcodes.IF_ICMPNE, fail)
            }

            Type.LONG, Type.DOUBLE, Type.FLOAT -> {
                step(
                    Insn.Plain(
                        if (type.sort ==
                            Type.LONG
                        ) {
                            Opcodes.LCMP
                        } else if (type.sort == Type.DOUBLE) {
                            Opcodes.DCMPL
                        } else {
                            Opcodes.FCMPL
                        },
                    ),
                )
                jump(Opcodes.IFNE, fail)
            }

            Type.ARRAY -> {
                jump(Opcodes.IF_ACMPNE, fail)
            }

            Type.OBJECT -> {
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
                                { nullSafeEquals(0, fail) },
                            )
                        } else {
                            nullSafeEquals(0, fail)
                        }
                    },
                )
            }

            else -> {
                fail()
            }
        }
    }

    private fun matchesCaseExtension(
        valueClass: ValueClass,
        name: String,
        descriptor: String,
        body: List<Insn>,
    ): Boolean {
        val type = valueClass.type
        val element = type.descriptor
        val size = type.size
        val scala3 = valueClass.companion.isScala3
        val string = "L$STRING;"
        val first = valueClass.load(1)
        return when (name) {
            "apply" -> {
                descriptor == "($element)$element" && body == listOf(first, valueClass.back())
            }

            "unapply" -> {
                when (descriptor) {
                    "($element)$element" -> scala3 && body == listOf(first, valueClass.back())
                    "($element)$OPTION_DESCRIPTOR" -> !scala3 && matchesUnapplyExtension(valueClass, body)
                    else -> false
                }
            }

            "canEqual$EXTENSION" -> {
                descriptor == "(${element}L$OBJECT;)Z" &&
                    body ==
                    listOf(
                        aload(1 + size),
                        Insn.TypeOperand(Opcodes.INSTANCEOF, if (scala3) valueClass.name else valueClass.boxedType),
                        Insn.Plain(Opcodes.IRETURN),
                    )
            }

            "productArity$EXTENSION" -> {
                descriptor == "($element)I" && body == listOf(Insn.IntConstant(1), Insn.Plain(Opcodes.IRETURN))
            }

            "productPrefix$EXTENSION" -> {
                descriptor == "($element)$string" && body == listOf(Insn.Constant(valueClass.shape.sourceName), areturn())
            }

            "toString$EXTENSION" -> {
                descriptor == "($element)$string" && body == runtimeCallOnNew(valueClass, "_toString", "(L$PRODUCT;)$string")
            }

            "productIterator$EXTENSION" -> {
                !scala3 && descriptor == "($element)$ITERATOR" &&
                    body == runtimeCallOnNew(valueClass, "typedProductIterator", "(L$PRODUCT;)$ITERATOR")
            }

            "productElement$EXTENSION" -> {
                descriptor == "(${element}I)L$OBJECT;" && matchesIndexedExtension(valueClass, body, names = false)
            }

            "productElementName$EXTENSION" -> {
                descriptor == "(${element}I)$string" && matchesIndexedExtension(valueClass, body, names = true)
            }

            "copy$EXTENSION" -> {
                descriptor == "($element$element)$element" && body == listOf(valueClass.load(1 + size), valueClass.back())
            }

            "copy\$default\$1$EXTENSION" -> {
                descriptor == "($element)$element" && body == listOf(first, valueClass.back())
            }

            "_1$EXTENSION" -> {
                scala3 && descriptor == "($element)$element" && body == listOf(first, valueClass.back())
            }

            else -> {
                false
            }
        }
    }

    private fun runtimeCallOnNew(
        valueClass: ValueClass,
        name: String,
        descriptor: String,
    ): List<Insn> =
        listOf(
            Insn.Field(Opcodes.GETSTATIC, RUNTIME, MODULE_FIELD, "L$RUNTIME;"),
            Insn.TypeOperand(Opcodes.NEW, valueClass.name),
            Insn.Plain(Opcodes.DUP),
            valueClass.load(1),
            Insn.Call(Opcodes.INVOKESPECIAL, valueClass.name, "<init>", "(${valueClass.type})V"),
            Insn.Call(Opcodes.INVOKEVIRTUAL, RUNTIME, name, descriptor),
            Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)),
        )

    private fun matchesUnapplyExtension(
        valueClass: ValueClass,
        body: List<Insn>,
    ): Boolean {
        val m = Match(body)
        m.step(Insn.TypeOperand(Opcodes.NEW, valueClass.name))
        m.step(Insn.Plain(Opcodes.DUP))
        m.step(valueClass.load(1))
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, valueClass.name, "<init>", "(${valueClass.type})V"))
        m.jump(Opcodes.IFNONNULL, "some")
        m.step(Insn.Field(Opcodes.GETSTATIC, NONE_OBJECT, MODULE_FIELD, "L$NONE_OBJECT;"))
        m.step(areturn())
        m.label("some")
        m.step(Insn.TypeOperand(Opcodes.NEW, SOME))
        m.step(Insn.Plain(Opcodes.DUP))
        m.step(valueClass.load(1))
        m.box(valueClass.type)
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, SOME, "<init>", "(L$OBJECT;)V"))
        m.step(areturn())
        return m.matched
    }

    private fun matchesIndexedExtension(
        valueClass: ValueClass,
        body: List<Insn>,
        names: Boolean,
    ): Boolean {
        val type = valueClass.type
        val index = 1 + type.size
        val scala3 = valueClass.companion.isScala3
        return listOf(true, false).any { tableSwitch ->
            if (!scala3 && !tableSwitch) return@any false
            val m = Match(body, firstLocal = index + 1)
            m.step(Insn.Var(Opcodes.ILOAD, index))
            m.store(Opcodes.ISTORE, "k")
            var shared = false
            val caseBody = {
                if (names) {
                    m.step(Insn.Constant(valueClass.field.name))
                    m.step(areturn())
                } else {
                    if (scala3) {
                        m.step(aload(0))
                        m.step(valueClass.load(1))
                        m.step(
                            Insn.Call(
                                Opcodes.INVOKEVIRTUAL,
                                valueClass.moduleName,
                                "_1$EXTENSION",
                                "(${valueClass.type})${valueClass.type}",
                            ),
                        )
                    } else {
                        m.step(valueClass.load(1))
                    }
                    val boxed =
                        m.optional {
                            box(type)
                            step(areturn())
                            true
                        }
                    if (boxed == null) {
                        if (!scala3 || type.sort !in Type.BOOLEAN..Type.DOUBLE) m.fail()
                        shared = true
                        m.jump(Opcodes.GOTO, "shared")
                    }
                }
            }
            if (tableSwitch) {
                m.load(Opcodes.ILOAD, "k")
                m.tableSwitch(0, listOf("case0"), "default")
                m.label("case0")
                caseBody()
            } else {
                m.step(Insn.IntConstant(0))
                m.load(Opcodes.ILOAD, "k")
                m.jump(Opcodes.IF_ICMPNE, "default")
                caseBody()
            }
            m.label("default")
            if (scala3) {
                m.step(Insn.TypeOperand(Opcodes.NEW, OUT_OF_BOUNDS))
                m.step(Insn.Plain(Opcodes.DUP))
                m.step(Insn.Var(Opcodes.ILOAD, index))
                m.either(
                    {
                        step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxToInteger", "(I)Ljava/lang/Integer;"))
                        step(Insn.Call(Opcodes.INVOKEVIRTUAL, INTEGER, "toString", "()L$STRING;"))
                        step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(L$STRING;)V"))
                    },
                    { step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(I)V")) },
                )
                m.step(Insn.Plain(Opcodes.ATHROW))
                if (shared) m.optional { step(Insn.Plain(Opcodes.ATHROW)) }
            } else {
                val ioobe: Match.() -> Unit = {
                    step(Insn.Var(Opcodes.ILOAD, index))
                    step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "ioobe", "(I)L$OBJECT;"))
                    if (names) step(Insn.TypeOperand(Opcodes.CHECKCAST, STRING))
                    step(areturn())
                }
                val scala212: Match.() -> Unit = {
                    step(Insn.TypeOperand(Opcodes.NEW, OUT_OF_BOUNDS))
                    step(Insn.Plain(Opcodes.DUP))
                    step(Insn.Var(Opcodes.ILOAD, index))
                    step(Insn.Call(Opcodes.INVOKESTATIC, INTEGER, "toString", "(I)L$STRING;"))
                    step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(L$STRING;)V"))
                    step(Insn.Plain(Opcodes.ATHROW))
                }
                if (!names && valueClass.mayBeScala212) m.either(ioobe, scala212) else m.ioobe()
            }
            if (shared) {
                m.label("shared")
                m.box(type)
                m.step(areturn())
            }
            m.matched
        }
    }

    private fun traitForwarders(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): TraitForwarders {
        if (shape.access and Opcodes.ACC_INTERFACE != 0) return TraitForwarders(traitEntries(shape), emptySet())
        val result = mutableSetOf<Pair<String, String>>()
        val undecided = mutableSetOf<Pair<String, String>>()
        var supertypes: Map<String, ClassShape?>? = null
        for ((key, method) in shape.methods) {
            val (name, descriptor) = key
            if (method.isStatic || name == "<init>" || DEFAULT_GETTER_NAME.matches(name)) continue
            val body = method.body ?: continue
            val call = body.getOrNull(body.size - 2) as? Insn.Call ?: continue
            val returnType = Type.getReturnType(descriptor)
            val loads = listOf(aload(0)) + parameterLoads(descriptor, 1)
            val end = Insn.Plain(returnType.getOpcode(Opcodes.IRETURN))
            if (specialisedTwinOf(shape, key) == call.name && body == loads + call + end &&
                call.opcode == Opcodes.INVOKEVIRTUAL && call.owner == shape.internalName && call.descriptor == descriptor
            ) {
                val declared = supertypes ?: supertypeShapes(shape, lookup).also { supertypes = it }
                when (declaresTwinAbove(declared.filterKeys { it != OBJECT }, call.name, descriptor)) {
                    true -> result += key
                    null -> undecided += key
                    false -> Unit
                }
                continue
            }
            if (call.opcode == Opcodes.INVOKESPECIAL && call.name == name && call.descriptor == descriptor && body == loads + call + end) {
                if (call.owner !in shape.interfaces) continue
                val declared = supertypes ?: supertypeShapes(shape, lookup).also { supertypes = it }
                val java = declared[call.owner]
                if (java == null || java.declaresDefault(key)) result += key
                continue
            }
            val trait = call.owner
            val entryName = "$name$"
            val entryDescriptor = "(L$trait;${parameterPart(descriptor)})$returnType"
            if (body != loads + Insn.Call(Opcodes.INVOKESTATIC, trait, entryName, entryDescriptor) + end) continue
            val known = supertypes ?: supertypeShapes(shape, lookup).also { supertypes = it }
            val declared = known[trait]
            if (declared == null) {
                if (trait in known || known.any { (name, header) -> header == null && name != OBJECT }) undecided += key
                continue
            }
            val entry = declared.methods[entryName to entryDescriptor]
            if (declared.access and Opcodes.ACC_INTERFACE != 0 && entry?.isStatic == true && declared.declaresDefault(key)) {
                result += key
            }
        }
        return TraitForwarders(result, undecided)
    }

    private class TraitForwarders(
        val marked: Set<Pair<String, String>>,
        val undecided: Set<Pair<String, String>>,
    )

    private fun ClassShape.declaresDefault(key: Pair<String, String>): Boolean {
        val method = methods[key]
        return access and Opcodes.ACC_INTERFACE != 0 && method != null && !method.isStatic && method.access and Opcodes.ACC_ABSTRACT == 0
    }

    private fun specialisedTwinOf(
        shape: ClassShape,
        key: Pair<String, String>,
    ): String? {
        val (name, descriptor) = key
        val types = listOf(Type.getReturnType(descriptor)) + Type.getArgumentTypes(descriptor)
        if (types.any { it.sort == Type.OBJECT || it.sort == Type.ARRAY || it.sort == Type.METHOD }) return null
        val twin = "$name\$mc${types.joinToString("") { it.descriptor }}\$sp"
        val method = shape.methods[twin to descriptor] ?: return null
        return twin.takeIf { !method.isStatic && method.access and Opcodes.ACC_ABSTRACT == 0 }
    }

    private fun declaresTwinAbove(
        declared: Map<String, ClassShape?>,
        twin: String,
        descriptor: String,
    ): Boolean? {
        if (declared.values.any { it?.methods?.get(twin to descriptor)?.isStatic == false }) return true
        return if (declared.values.any { it == null }) null else false
    }

    private fun traitEntries(shape: ClassShape): Set<Pair<String, String>> =
        shape.methods
            .filter { (key, method) ->
                val (name, descriptor) = key
                val parameters = Type.getArgumentTypes(descriptor)
                val body = method.body
                if (!method.isStatic || body == null || !name.endsWith("$") || name.length < 2 || parameters.isEmpty()) {
                    false
                } else {
                    val base = name.dropLast(1)
                    val defaultDescriptor = "(${parameterPart(
                        descriptor,
                    ).removePrefix(parameters[0].descriptor)})${Type.getReturnType(descriptor)}"
                    val default = shape.methods[base to defaultDescriptor]
                    parameters[0].descriptor == "L${shape.internalName};" &&
                        !DEFAULT_GETTER_NAME.matches(base) &&
                        default != null &&
                        !default.isStatic &&
                        default.access and Opcodes.ACC_ABSTRACT == 0 &&
                        body ==
                        parameterLoads(descriptor, 0) +
                        Insn.Call(Opcodes.INVOKESPECIAL, shape.internalName, base, defaultDescriptor) +
                        Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))
                }
            }.keys

    private fun supertypeShapes(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): Map<String, ClassShape?> {
        val seen = LinkedHashMap<String, ClassShape?>()
        val queue = ArrayDeque(listOfNotNull(shape.superName) + shape.interfaces)
        while (queue.isNotEmpty() && seen.size < MAX_SUPERTYPES) {
            val name = queue.removeFirst()
            if (name in seen) continue
            val header = readClass(name, lookup, bodiesOf = emptySet())
            seen[name] = header
            header?.superName?.let(queue::add)
            if (header != null) queue.addAll(header.interfaces)
        }
        return seen
    }

    private const val MAX_SUPERTYPES = 64

    private class Marks(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val passThrough: Set<Pair<String, String>>,
        val undecided: Set<Pair<String, String>> = emptySet(),
    )

    private fun marksOf(
        shape: ClassShape,
        lookup: (internalName: String) -> ByteArray?,
        sides: ValueSides = valueSidesOf(shape, lookup),
    ): Marks {
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
        val forwarders = forwarderMarks(shape, lookup, sides)
        for ((key, mark) in forwarders.generated) result.putIfAbsent(key, mark)
        sides.companion?.let { valueClass ->
            for ((key, mark) in valueClassCompanionMarks(valueClass)) result.putIfAbsent(key, mark)
        }
        if (shape.isModuleClass && shape.writeReplaceMatches()) result.putIfAbsent(WRITE_REPLACE, GeneratedBy.SCALA_OBJECT)
        if (!shape.isScala3 && shape.isModuleClass && !shape.writeReplaceMatches() && shape.readResolveMatches()) {
            result.putIfAbsent(READ_RESOLVE, GeneratedBy.SCALA_OBJECT)
        }
        return Marks(result, forwarders.passThrough, forwarders.undecided)
    }

    private fun outlineOf(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
        generated: Map<Pair<String, String>, GeneratedBy>,
        sides: ValueSides,
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
        add(enumOutline(shape, lookup, generated), UnreadShape.SCALA_ENUM)
        caseClassOf(shape)?.let { add(caseClassOutline(it), UnreadShape.CASE_CLASS) }
        sides.boxed?.takeIf { it.isCase }?.let { add(valueCaseOutline(it), UnreadShape.CASE_CLASS) }
        sides.companion?.takeIf { it.isCase }?.let { add(valueCaseCompanionOutline(it), UnreadShape.CASE_CLASS) }
        if (shape.internalName.endsWith("$") && open.any { (key, method) -> !method.isStatic && key.first in COMPANION_METHODS }) {
            readClass(partnerName(shape), lookup)?.let(::caseClassOf)?.takeIf { !it.isObject }?.let { partner ->
                add(companionOutline(shape, partner), UnreadShape.CASE_CLASS)
            }
        }
        if (shape.isModuleClass && (shape.isScala3 || shape.isSerializable)) {
            add(listOf(WRITE_REPLACE), UnreadShape.SCALA_OBJECT)
            if (!shape.isScala3 && !shape.writeReplaceMatches()) add(listOf(READ_RESOLVE), UnreadShape.SCALA_OBJECT)
        }
        return result
    }

    private fun forwarderOutlineOf(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
        generated: Map<Pair<String, String>, GeneratedBy>,
        sides: ValueSides,
    ): Map<Pair<String, String>, UnreadShape> {
        val open = shape.methods.filterKeys { it !in generated }
        val result = LinkedHashMap<Pair<String, String>, UnreadShape>()

        fun add(
            keys: Collection<Pair<String, String>>,
            family: UnreadShape,
            static: Boolean = false,
        ) {
            for (key in keys) if (open[key]?.isStatic == static) result.putIfAbsent(key, family)
        }
        sides.boxed?.let { add(valueClassBoxedOutline(it), UnreadShape.VALUE_CLASS) }
        sides.companion?.let { add(valueClassCompanionOutline(it), UnreadShape.VALUE_CLASS) }
        add(traitForwarderOutline(shape, lookup), UnreadShape.TRAIT_FORWARDER)
        add(traitEntryOutline(shape), UnreadShape.TRAIT_FORWARDER, static = true)
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

    private fun enumOutline(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
        generated: Map<Pair<String, String>, GeneratedBy>,
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
            val enumClass = pairEnumCompanion(shape, lookup)?.enumClass ?: return emptyList()
            val enumType = "L${enumClass.internalName};"
            val cases = shape.enumCases(enumClass.internalName, lookup)
            return listOf(
                "fromOrdinal" to "(I)$enumType",
                "ordinal" to "($enumType)I",
                "ordinal" to "($objectType)I",
            ) + companionCaseKeys(shape, enumClass, cases, generated) + enumCaseAccessorKeys(shape, enumClass, lookup)
        }
        val singleton = ENUM_VALUE in shape.interfaces && MIRROR_SINGLETON in shape.interfaces && shape.isFinal
        val enumClass = shape.superName?.let { readClass(it, lookup) }?.takeIf { it.isEnumClass() } ?: return emptyList()
        if (!singleton) {
            val fits = shape.isFinal && ENUM_VALUE !in shape.interfaces && shape.methods[ORDINAL]?.isStatic == false
            return if (fits) listOf(ORDINAL) else emptyList()
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

    private fun companionCaseKeys(
        shape: ClassShape,
        enumClass: ClassShape,
        cases: List<EnumCase>?,
        generated: Map<Pair<String, String>, GeneratedBy>,
    ): List<Pair<String, String>> {
        val enumType = "L${enumClass.internalName};"
        val unknown = cases.isNullOrEmpty()
        val allSingleton = unknown || cases.all { it is EnumCase.Singleton }
        val anySingleton = unknown || cases.any { it is EnumCase.Singleton }
        val valueOf = shape.methods.keys.filter(::isCompanionValueOf)
        return listOfNotNull(
            ("values" to "()[$enumType").takeIf { allSingleton },
            ("\$new" to "(IL$STRING;)$enumType").takeIf { anySingleton },
        ) + if (allSingleton && valueOf.none { it in generated }) valueOf else emptyList()
    }

    private fun isCompanionValueOf(key: Pair<String, String>): Boolean = key.first == "valueOf" && VALUE_OF_DESCRIPTOR.matches(key.second)

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

    private fun valueClassBoxedOutline(valueClass: ValueClass): List<Pair<String, String>> =
        valueClass.shape.methods.keys.filter { (name, descriptor) ->
            val method = valueClass.shape.methods.getValue(name to descriptor)
            val targetDescriptor = "(${valueClass.type}${parameterPart(descriptor)})${Type.getReturnType(descriptor)}"
            !method.isStatic &&
                name != "<init>" &&
                name != "<clinit>" &&
                valueClass.companion.methods.any { (target, extension) ->
                    !extension.isStatic && target.second == targetDescriptor && isExtensionOf(name, target.first)
                }
        }

    private fun valueClassCompanionOutline(valueClass: ValueClass): List<Pair<String, String>> {
        val element = valueClass.type.descriptor
        return listOf("hashCode$EXTENSION" to "($element)I", "equals$EXTENSION" to "(${element}L$OBJECT;)Z")
    }

    private fun traitForwarderOutline(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): List<Pair<String, String>> {
        if (shape.access and Opcodes.ACC_INTERFACE != 0 || shape.interfaces.isEmpty()) return emptyList()
        val traits = mutableListOf<ClassShape>()
        val seen = LinkedHashMap<String, ClassShape?>()
        val queue = ArrayDeque(shape.interfaces)
        while (queue.isNotEmpty() && seen.size < MAX_SUPERTYPES) {
            val name = queue.removeFirst()
            if (name in seen) continue
            val header = readClass(name, lookup, bodiesOf = emptySet())
            seen[name] = header
            if (header == null || header.access and Opcodes.ACC_INTERFACE == 0) continue
            traits += header
            queue.addAll(header.interfaces)
        }
        val supertypes by lazy { supertypeShapes(shape, lookup) }
        return shape.methods.keys.filter { key ->
            val (name, descriptor) = key
            val method = shape.methods.getValue(key)
            !method.isStatic &&
                name != "<init>" &&
                !DEFAULT_GETTER_NAME.matches(name) &&
                (
                    callsSpecialisedTwinIn(shape, key) { supertypes } ||
                        method.code.orEmpty().any {
                            it is Insn.Call && callsJavaDefault(shape, key, it) &&
                                seen[it.owner].let { java -> java == null || java.declaresDefault(key) }
                        } ||
                        traits.any { trait ->
                            val entryDescriptor = "(L${trait.internalName};${parameterPart(descriptor)})${Type.getReturnType(descriptor)}"
                            val default = trait.methods[key]
                            trait.methods["$name$" to entryDescriptor]?.isStatic == true &&
                                default != null &&
                                !default.isStatic &&
                                default.access and Opcodes.ACC_ABSTRACT == 0 &&
                                method.code?.contains(Insn.Call(Opcodes.INVOKESTATIC, trait.internalName, "$name$", entryDescriptor)) ==
                                true
                        }
                )
        }
    }

    private fun callsJavaDefault(
        shape: ClassShape,
        key: Pair<String, String>,
        call: Insn.Call,
    ): Boolean =
        call.opcode == Opcodes.INVOKESPECIAL && call.name == key.first && call.descriptor == key.second && call.owner in shape.interfaces

    private fun callsSpecialisedTwin(
        shape: ClassShape,
        key: Pair<String, String>,
        call: Insn.Call,
    ): Boolean =
        call.opcode == Opcodes.INVOKEVIRTUAL &&
            call.owner == shape.internalName &&
            call.descriptor == key.second &&
            call.name == specialisedTwinOf(shape, key)

    private fun callsSpecialisedTwinIn(
        shape: ClassShape,
        key: Pair<String, String>,
        supertypes: () -> Map<String, ClassShape?>,
    ): Boolean =
        shape.methods
            .getValue(key)
            .code
            .orEmpty()
            .any { it is Insn.Call && callsSpecialisedTwin(shape, key, it) } &&
            declaresTwinAbove(supertypes(), specialisedTwinOf(shape, key).orEmpty(), key.second) != false

    private fun traitEntryOutline(shape: ClassShape): List<Pair<String, String>> {
        if (shape.access and Opcodes.ACC_INTERFACE == 0) return emptyList()
        return shape.methods.keys.filter { key ->
            val (name, descriptor) = key
            val parameters = Type.getArgumentTypes(descriptor)
            if (!shape.methods.getValue(key).isStatic || !name.endsWith("$") || name.length < 2 || parameters.isEmpty()) {
                false
            } else {
                val base = name.dropLast(1)
                val twin = "(${parameterPart(descriptor).removePrefix(parameters[0].descriptor)})${Type.getReturnType(descriptor)}"
                val default = shape.methods[base to twin]
                parameters[0].descriptor == "L${shape.internalName};" &&
                    !DEFAULT_GETTER_NAME.matches(base) &&
                    default != null &&
                    !default.isStatic &&
                    default.access and Opcodes.ACC_ABSTRACT == 0
            }
        }
    }

    private fun valueCaseOutline(valueClass: ValueClass): List<Pair<String, String>> {
        val element = valueClass.type.descriptor
        return listOf("copy" to "($element)$element", "copy\$default\$1" to "()$element")
    }

    private fun valueCaseCompanionOutline(valueClass: ValueClass): List<Pair<String, String>> {
        val element = valueClass.type.descriptor
        val scala3 = valueClass.companion.isScala3
        val objectType = "L$OBJECT;"
        val string = "L$STRING;"
        return listOf(
            "apply" to "($element)$element",
            "unapply" to if (scala3) "($element)$element" else "($element)$OPTION_DESCRIPTOR",
            "hashCode$EXTENSION" to "($element)I",
            "equals$EXTENSION" to "($element$objectType)Z",
            "toString$EXTENSION" to "($element)$string",
            "canEqual$EXTENSION" to "($element$objectType)Z",
            "productArity$EXTENSION" to "($element)I",
            "productPrefix$EXTENSION" to "($element)$string",
            "productElement$EXTENSION" to "(${element}I)$objectType",
            "productElementName$EXTENSION" to "(${element}I)$string",
            "copy$EXTENSION" to "($element$element)$element",
            "copy\$default\$1$EXTENSION" to "($element)$element",
            if (scala3) "_1$EXTENSION" to "($element)$element" else "productIterator$EXTENSION" to "($element)$ITERATOR",
        )
    }

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

    private fun sourceName(
        internalName: String,
        entry: InnerClassEntry?,
    ): String {
        val simple = entry?.innerName ?: internalName.substringAfterLast('/')
        val name = if (internalName.endsWith("$")) simple.removeSuffix("$") else simple
        return if (entry != null && entry.outerName == null) name.replace(LOCAL_SUFFIX, "") else name
    }

    private val LOCAL_SUFFIX = Regex("\\$\\d+$")

    private data class EnclosingMethod(
        val owner: String,
        val name: String?,
        val descriptor: String?,
    )

    private class FieldShape(
        val access: Int,
        val name: String,
        val descriptor: String,
    )

    private data class Handler(
        val start: Int,
        val end: Int,
        val target: Int,
        val type: String?,
    )

    private class InnerClassEntry(
        val name: String,
        val outerName: String?,
        val innerName: String?,
        val access: Int,
    )

    private class MethodShape(
        val access: Int,
    ) {
        var code: List<Insn>? = null
        var hasHandler = false
        var handlers: List<Handler> = emptyList()

        val body: List<Insn>? get() = code?.takeIf { !hasHandler }

        var delegatesToOwnConstructor = false

        val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
        val isPrivate: Boolean get() = access and Opcodes.ACC_PRIVATE != 0
    }

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
        val enclosingMethod: EnclosingMethod? = null,
    ) {
        val isModuleClass: Boolean get() = internalName.endsWith("$") && hasOwnModuleField

        fun isPrimaryConstructor(descriptor: String): Boolean = methods["<init>" to descriptor]?.delegatesToOwnConstructor == false

        val sourceName: String get() = sourceName(internalName, innerClasses.firstOrNull { it.name == internalName })

        fun instanceMethods(): List<Pair<Pair<String, String>, List<Insn>>> =
            methods.mapNotNull { (key, method) -> method.body?.takeIf { !method.isStatic }?.let { key to it } }

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

        fun readResolveMatches(): Boolean {
            val method = methods[READ_RESOLVE] ?: return false
            return method.isPrivate &&
                method.body ==
                listOf(Insn.Field(Opcodes.GETSTATIC, internalName, MODULE_FIELD, "L$internalName;"), Insn.Plain(Opcodes.ARETURN))
        }
    }

    private fun readShape(
        classBytes: ByteArray,
        bodiesOf: Set<Pair<String, String>>? = null,
    ): ClassShape {
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
        var enclosingMethod: EnclosingMethod? = null

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
                    innerClasses += InnerClassEntry(name, outerName, innerName, access)
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    enclosingMethod = EnclosingMethod(owner, name, descriptor)
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
                ): MethodVisitor? {
                    val method = MethodShape(access)
                    methods[name to descriptor] = method
                    if (bodiesOf != null && name to descriptor !in bodiesOf) return null
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
            enclosingMethod,
        )
    }

    private class Element(
        val index: Int,
        val name: String,
        val type: Type,
        val accessor: String,
        private val accessorPrivate: Boolean,
        private val owner: String,
    ) {
        val read: Insn get() = Insn.Call(if (accessorPrivate) Opcodes.INVOKESPECIAL else Opcodes.INVOKEVIRTUAL, owner, accessor, "()$type")

        val readFromOutside: Insn? get() = if (accessorPrivate) null else read

        val isPrimitive: Boolean get() = type.sort in Type.BOOLEAN..Type.DOUBLE
    }

    private class CaseClass(
        val shape: ClassShape,
    ) {
        val name: String get() = shape.internalName
        val isObject: Boolean get() = shape.isModuleClass

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

        val hasGeneratedCanEqual: Boolean
            get() = shape.methods["canEqual" to "(L$OBJECT;)Z"]?.takeIf { !it.isStatic }?.body == generatedCanEqual(name)

        val equalsMayOmitCanEqual: Boolean get() = shape.isFinal && hasGeneratedCanEqual
    }

    private fun enumPlumbing(
        shape: ClassShape,
        lookup: (String) -> ByteArray?,
    ): Set<Pair<String, String>> {
        if (!shape.isScala || !shape.isScala3) return emptySet()
        if (shape.isEnumClass()) return enumClassPlumbing(shape)
        if (shape.internalName.endsWith("$") && MIRROR_SUM in shape.interfaces) {
            val pairing = pairEnumCompanion(shape, lookup)?.takeIf { it.certain } ?: return emptySet()
            val enumName = pairing.enumClass.internalName
            return enumCompanionPlumbing(EnumFamily(pairing.enumClass, shape, shape.enumCases(enumName, lookup), pairing.local), lookup)
        }
        val singleton = ENUM_VALUE in shape.interfaces && MIRROR_SINGLETON in shape.interfaces && shape.isFinal
        if (!singleton && !shape.declaresConstantOrdinal()) return emptySet()
        val superName = shape.superName ?: return emptySet()
        val enumClass = readClass(superName, lookup)?.takeIf { it.isEnumClass() } ?: return emptySet()
        val family = enumFamilyOf(shape, singleton, enumClass, lookup) ?: return emptySet()
        return if (singleton) singletonCasePlumbing(shape, family) else parameterisedCasePlumbing(shape, family)
    }

    private fun ClassShape.isEnumClass(): Boolean = isEnumClass(access, interfaces)

    private fun isEnumClass(
        access: Int,
        interfaces: List<String>,
    ): Boolean = access and Opcodes.ACC_ENUM != 0 && access and Opcodes.ACC_ABSTRACT != 0 && ENUM in interfaces

    private fun ClassShape.enumCases(
        enumName: String,
        lookup: (String) -> ByteArray?,
    ): List<EnumCase>? {
        val parameterised = mutableListOf<EnumCase.Parameterised>()
        for (entry in parameterisedCaseCandidates(enumName)) {
            val case = readClass(entry.name, lookup, bodiesOf = setOf(ORDINAL)) ?: return null
            if (case.superName != enumName || !case.isFinal || ENUM_VALUE in case.interfaces) continue
            val ordinal = case.constantOrdinal() ?: return null
            parameterised += EnumCase.Parameterised(entry.innerName!!, ordinal, entry.name)
        }
        val enumType = "L$enumName;"
        val singletons =
            if (isModuleClass) {
                fields.filter {
                    it.access and PLAIN_CASE_FIELD_FLAGS == PLAIN_CASE_FIELD_FLAGS &&
                        it.access and Opcodes.ACC_ENUM != 0 &&
                        it.name != MODULE_FIELD
                }
            } else {
                fields.filter { field ->
                    field.access and FIELD_KIND_FLAGS == INSTANCE_CASE_FIELD_FLAGS &&
                        field.descriptor == enumType &&
                        methods[field.name to "()$enumType"]?.let { !it.isStatic && it.access and ENUM_CASE_ACCESSOR_FLAG != 0 } == true
                }
            }
        val count = singletons.size + parameterised.size
        val taken = parameterised.map { it.ordinal }
        if (taken.toSet().size != taken.size || taken.any { it !in 0 until count }) return null
        val free = (0 until count).filter { it !in taken }
        return (singletons.zip(free) { field, ordinal -> EnumCase.Singleton(field.name, ordinal, field) } + parameterised)
            .sortedBy { it.ordinal }
    }

    private fun ClassShape.parameterisedCaseCandidates(enumName: String): List<InnerClassEntry> =
        innerClasses
            .filter {
                (it.outerName == enumName || it.outerName == internalName) &&
                    it.access and Opcodes.ACC_FINAL != 0 &&
                    it.innerName?.endsWith("$") == false
            }.distinctBy { it.name }

    private sealed class EnumCase(
        val name: String,
        val ordinal: Int,
    ) {
        class Singleton(
            name: String,
            ordinal: Int,
            val field: FieldShape,
        ) : EnumCase(name, ordinal)

        class Parameterised(
            name: String,
            ordinal: Int,
            val className: String,
        ) : EnumCase(name, ordinal)
    }

    private fun ClassShape.declaresConstantOrdinal(): Boolean = constantOrdinal() != null

    private fun ClassShape.constantOrdinal(): Int? {
        val body = methods[ORDINAL]?.body ?: return null
        return if (body.size == 2 && body[1] == Insn.Plain(Opcodes.IRETURN)) (body[0] as? Insn.IntConstant)?.value else null
    }

    private fun readOnce(lookup: (String) -> ByteArray?): (String) -> ByteArray? {
        val read = HashMap<String, ByteArray?>()
        return { internalName ->
            if (internalName in read) {
                read[internalName]
            } else {
                val bytes =
                    try {
                        lookup(internalName)
                    } catch (_: Exception) {
                        null
                    }
                read[internalName] = bytes
                bytes
            }
        }
    }

    private fun readClass(
        internalName: String,
        lookup: (String) -> ByteArray?,
        bodiesOf: Set<Pair<String, String>>? = null,
    ): ClassShape? =
        try {
            lookup(internalName)?.let { readShape(it, bodiesOf) }
        } catch (_: Exception) {
            null
        }

    private class EnumFamily(
        val enumClass: ClassShape,
        val companion: ClassShape,
        val cases: List<EnumCase>?,
        val local: Boolean = false,
        val hostInterface: Boolean = false,
    ) {
        val host: String? get() = if (local) companion.enclosingMethod?.owner else null

        val singletonType: String = "L${enumClass.internalName};"

        val instanceCases: Boolean get() = !companion.isModuleClass
        val singletons: List<EnumCase.Singleton>? get() = cases?.filterIsInstance<EnumCase.Singleton>()

        val allSingleton: Boolean get() = !cases.isNullOrEmpty() && cases.all { it is EnumCase.Singleton }

        fun singletonOrdinalOf(name: String): Int = singletons?.firstOrNull { it.name == name }?.ordinal ?: -1
    }

    private fun enumFamilyOf(
        caseShape: ClassShape,
        singleton: Boolean,
        enumClass: ClassShape,
        lookup: (String) -> ByteArray?,
    ): EnumFamily? {
        val named = readClass("${enumClass.internalName}$", lookup)
        if (named != null) {
            if (!named.isScala3 || MIRROR_SUM !in named.interfaces) return null
            return EnumFamily(enumClass, named, named.enumCases(enumClass.internalName, lookup))
        }
        val companionName =
            if (singleton) {
                caseShape.enclosingMethod?.owner
            } else {
                caseShape.innerClasses.firstOrNull { it.name == caseShape.internalName }?.outerName
            }
        val companion = companionName?.let { readClass(it, lookup) } ?: return null
        val pairing =
            pairEnumCompanion(companion, lookup)?.takeIf { it.certain && it.enumClass.internalName == enumClass.internalName }
                ?: return null
        val hostInterface =
            companion.enclosingMethod?.owner?.let { host ->
                try {
                    lookup(host)?.let { ClassReader(it).access and Opcodes.ACC_INTERFACE != 0 }
                } catch (_: Exception) {
                    null
                }
            } == true
        return EnumFamily(enumClass, companion, companion.enumCases(enumClass.internalName, lookup), pairing.local, hostInterface)
    }

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

                    isCompanionValueOf(key) -> {
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
        if (family.instanceCases) {
            for ((key, expected) in enumCaseAccessors(family)) {
                if (companion.methods[key]?.takeIf { !it.isStatic }?.body == expected) result += key
            }
        }
        return result
    }

    private fun enumCaseAccessors(family: EnumFamily): Map<Pair<String, String>, List<Insn>> {
        val companion = family.companion
        val result = LinkedHashMap<Pair<String, String>, List<Insn>>()

        fun accessor(
            name: String,
            field: String,
            type: String,
        ) {
            result[name to "()$type"] =
                listOf(aload(0), Insn.Field(Opcodes.GETFIELD, companion.internalName, field, type), areturn())
        }
        for (case in family.cases.orEmpty()) {
            when (case) {
                is EnumCase.Singleton -> {
                    accessor(case.name, case.field.name, case.field.descriptor)
                }

                is EnumCase.Parameterised -> {
                    val type = "L${case.className}$;"
                    caseObjectField(companion, case.name, type)?.let { accessor(case.name, it, type) }
                }
            }
        }
        return result
    }

    private fun caseObjectField(
        companion: ClassShape,
        caseName: String,
        type: String,
    ): String? {
        val read = companion.methods[caseName to "()$type"]?.body?.getOrNull(1) as? Insn.Field ?: return null
        val prefix = "$caseName\$lzy"
        val suffix = read.name.removePrefix(prefix)
        val named = read.name.startsWith(prefix) && suffix.isNotEmpty() && suffix.all(Char::isDigit)
        val declared = companion.fields.any { it.name == read.name && it.descriptor == type && it.access and Opcodes.ACC_STATIC == 0 }
        return read.name.takeIf { named && declared }
    }

    private fun enumCaseAccessorKeys(
        shape: ClassShape,
        enumClass: ClassShape,
        lookup: (String) -> ByteArray?,
    ): Set<Pair<String, String>> {
        if (shape.isModuleClass) return emptySet()
        val enumType = "L${enumClass.internalName};"
        val result = mutableSetOf<Pair<String, String>>()
        for ((key, method) in shape.methods) {
            if (!method.isStatic && key.second == "()$enumType" && method.access and ENUM_CASE_ACCESSOR_FLAG != 0) result += key
        }
        for (entry in shape.parameterisedCaseCandidates(enumClass.internalName)) {
            val key = entry.innerName!! to "()L${entry.name}$;"
            if (shape.methods[key]?.isStatic != false) continue
            val case =
                try {
                    lookup(entry.name)?.let(::ClassReader)
                } catch (_: Exception) {
                    null
                }
            if (case == null || (case.superName == enumClass.internalName && ENUM_VALUE !in case.interfaces)) result += key
        }
        return result
    }

    private fun matchesEnumValues(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val array = "[${family.singletonType}"
        return family.allSingleton &&
            family.hasValuesField() &&
            method.body ==
            family.loadValues() +
            listOf(
                Insn.Call(Opcodes.INVOKEVIRTUAL, array, "clone", "()L$OBJECT;"),
                Insn.TypeOperand(Opcodes.CHECKCAST, array),
                Insn.Plain(Opcodes.ARETURN),
            )
    }

    private fun EnumFamily.hasValuesField(): Boolean =
        companion.fields.singleOrNull { it.name == VALUES_FIELD && it.descriptor == "[$singletonType" }?.let {
            (it.access and Opcodes.ACC_STATIC != 0) != instanceCases
        } == true

    private fun EnumFamily.loadValues(): List<Insn> {
        val array = "[$singletonType"
        return if (instanceCases) {
            listOf(aload(0), Insn.Field(Opcodes.GETFIELD, companion.internalName, VALUES_FIELD, array))
        } else {
            listOf(Insn.Field(Opcodes.GETSTATIC, companion.internalName, VALUES_FIELD, array))
        }
    }

    private fun namesEnum(
        name: String,
        family: EnumFamily,
    ): Boolean {
        val enumClass = family.enumClass
        if (!family.local) return name.substringAfterLast('.') == enumClass.sourceName
        val pkg = enumClass.internalName.substringBeforeLast('/', "").replace('/', '.')
        val prefix = if (pkg.isEmpty()) "" else "$pkg."
        return name.startsWith(prefix) &&
            ' ' !in name &&
            Regex("\\.(?:_\\$)+" + Regex.escape(enumClass.sourceName) + "$").containsMatchIn(name)
    }

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
                    namesEnum(recipe.removePrefix("enum ").removeSuffix(tail), family)
            }
        }
    }

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

    private fun Match.returnCase(
        family: EnumFamily,
        case: EnumCase.Singleton,
    ) {
        step(aload(0))
        if (family.instanceCases) {
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, family.companion.internalName, case.name, "()${case.field.descriptor}"))
        } else {
            step(Insn.Plain(Opcodes.POP))
            step(Insn.Field(Opcodes.GETSTATIC, family.companion.internalName, case.name, case.field.descriptor))
        }
        step(areturn())
    }

    private fun matchesEnumValueOf(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val body = method.body ?: return false
        if (!family.allSingleton) return false
        val cases = family.singletons ?: return false
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

    private fun matchesEnumFromOrdinal(
        family: EnumFamily,
        method: MethodShape,
    ): Boolean {
        val code = method.code ?: return false
        if (family.allSingleton && family.hasValuesField()) {
            val m = Match(code)
            for (load in family.loadValues()) m.step(load)
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
            if (m.matched && handler != null && method.handlers == listOf(Handler(0, family.loadValues().size + 2, handler, THROWABLE))) {
                return true
            }
        }
        if (method.hasHandler) return false
        val singletons = family.singletons ?: return false
        if (singletons.isEmpty()) {
            return Match(code).also { it.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal") }.matched
        }
        val chain = Match(code)
        chain.step(Insn.Var(Opcodes.ILOAD, 1))
        chain.store(Opcodes.ISTORE, "k")
        for (case in singletons) {
            chain.step(Insn.IntConstant(case.ordinal))
            chain.load(Opcodes.ILOAD, "k")
            chain.jump(Opcodes.IF_ICMPNE, "next${case.ordinal}")
            chain.returnCase(family, case)
            chain.label("next${case.ordinal}")
        }
        chain.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal")
        if (chain.matched) return true
        val switch = Match(code)
        switch.step(Insn.Var(Opcodes.ILOAD, 1))
        switch.store(Opcodes.ISTORE, "k")
        switch.load(Opcodes.ILOAD, "k")
        switch.keyedSwitch(singletons.map { it.ordinal }, singletons.map { "case${it.ordinal}" }, "default")
        for (case in singletons) {
            switch.label("case${case.ordinal}")
            switch.returnCase(family, case)
        }
        switch.label("default")
        switch.throwNoCase(family, NO_SUCH_ELEMENT, "ordinal")
        return switch.matched
    }

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
                (
                    !family.instanceCases &&
                        body ==
                        listOf(
                            Insn.TypeOperand(Opcodes.NEW, created),
                            Insn.Plain(Opcodes.DUP),
                            aload(2),
                            Insn.Var(Opcodes.ILOAD, 1),
                            Insn.Call(Opcodes.INVOKESPECIAL, created, "<init>", "(L$STRING;I)V"),
                            areturn(),
                        )
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
            if (family.local) {
                localReadResolve(shape, family, readResolve?.body)
            } else {
                val companionLoad =
                    if (family.instanceCases) {
                        val outer = shape.fields.singleOrNull { it.name == OUTER_FIELD && it.access and Opcodes.ACC_STATIC == 0 }
                        outer?.takeIf { it.descriptor == "L$companion;" }?.let {
                            listOf(
                                aload(0),
                                Insn.Field(Opcodes.GETFIELD, self, OUTER_FIELD, it.descriptor),
                            )
                        }
                    } else {
                        listOf(Insn.Field(Opcodes.GETSTATIC, companion, MODULE_FIELD, "L$companion;"))
                    }
                companionLoad?.let { it + resolvedOrdinal(self, companion, enumType) }
            }
        if (readResolve != null && readResolve.isPrivate && resolves != null && readResolve.body == resolves) result += READ_RESOLVE

        if (family.enumClass.superName == JAVA_ENUM) {
            val nameCall = listOf(aload(0), Insn.Call(Opcodes.INVOKEVIRTUAL, self, "name", "()L$STRING;"), areturn())
            if (shape.methods["productPrefix" to "()L$STRING;"]?.takeIf { !it.isStatic }?.body == nameCall) {
                result += "productPrefix" to "()L$STRING;"
            }
            return result
        }
        val nameLoad = singletonNameLoad(shape, family) ?: return result
        val ordinalKey = "ordinal" to "()I"
        val ordinalBody = shape.methods[ordinalKey]?.takeIf { !it.isStatic }?.body
        val ordinalMatches =
            when (val first = nameLoad.first()) {
                is Insn.Constant -> {
                    ordinalBody ==
                        listOf(Insn.IntConstant(family.singletonOrdinalOf(first.value as String)), Insn.Plain(Opcodes.IRETURN))
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

    private fun resolvedOrdinal(
        self: String,
        companion: String,
        enumType: String,
    ): List<Insn> =
        listOf(
            aload(0),
            Insn.Call(Opcodes.INVOKEVIRTUAL, self, "ordinal", "()I"),
            Insn.Call(Opcodes.INVOKEVIRTUAL, companion, "fromOrdinal", "(I)$enumType"),
            areturn(),
        )

    private fun localReadResolve(
        shape: ClassShape,
        family: EnumFamily,
        body: List<Insn>?,
    ): List<Insn>? {
        val host = family.host ?: return null
        body ?: return null
        val self = shape.internalName
        val companion = family.companion
        val companionType = "L${companion.internalName};"
        val hostType = "L$host;"
        val lazyRef = "L$LAZY_REF;"
        val outer =
            shape.fields
                .singleOrNull { it.name == OUTER_FIELD && it.access and Opcodes.ACC_STATIC == 0 }
                ?.takeIf { it.descriptor == companionType }
        val loadOuter = listOf(aload(0), Insn.Field(Opcodes.GETFIELD, self, OUTER_FIELD, companionType))

        fun declaresLazyRef(
            owner: ClassShape,
            name: String,
        ): Boolean = owner.fields.any { it.name == name && it.descriptor == lazyRef && it.access and Opcodes.ACC_STATIC == 0 }
        val hostLoad =
            if (body.firstOrNull() == Insn.Field(Opcodes.GETSTATIC, host, MODULE_FIELD, hostType)) {
                body.take(1)
            } else {
                val accessor = (body.getOrNull(2) as? Insn.Call)?.name ?: return null
                val read = Insn.Call(Opcodes.INVOKEVIRTUAL, companion.internalName, accessor, "()$hostType")
                if (outer == null || companion.methods[accessor to "()$hostType"]?.isStatic != false) return null
                loadOuter + read
            }
        val rest = body.drop(hostLoad.size)
        val lazyLoad =
            if (outer != null && rest.getOrNull(1) == loadOuter[1]) {
                val name = (rest.getOrNull(2) as? Insn.Field)?.name ?: return null
                if (!declaresLazyRef(companion, name)) return null
                loadOuter + Insn.Field(Opcodes.GETFIELD, companion.internalName, name, lazyRef)
            } else {
                val name = (rest.getOrNull(1) as? Insn.Field)?.name ?: return null
                if (!declaresLazyRef(shape, name)) return null
                listOf(aload(0), Insn.Field(Opcodes.GETFIELD, self, name, lazyRef))
            }
        val lazyAccessor = (rest.getOrNull(lazyLoad.size) as? Insn.Call)?.name ?: return null
        return hostLoad +
            lazyLoad +
            Insn.Call(
                if (family.hostInterface) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL,
                host,
                lazyAccessor,
                "($lazyRef)$companionType",
            ) +
            resolvedOrdinal(self, companion.internalName, family.singletonType)
    }

    private fun singletonNameLoad(
        shape: ClassShape,
        family: EnumFamily,
    ): List<Insn>? {
        val body = shape.methods["productPrefix" to "()L$STRING;"]?.takeIf { !it.isStatic }?.body ?: return null
        if (body.size == 2 && body[1] == areturn()) {
            val name = (body[0] as? Insn.Constant)?.value as? String ?: return null
            return if (family.singletonOrdinalOf(name) >= 0) listOf(body[0]) else null
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

    private fun parameterisedCasePlumbing(
        shape: ClassShape,
        family: EnumFamily,
    ): Set<Pair<String, String>> {
        if (!shape.isFinal || ENUM_VALUE in shape.interfaces) return emptySet()
        val case = family.cases?.filterIsInstance<EnumCase.Parameterised>()?.firstOrNull { it.className == shape.internalName }
        return if (case != null && shape.methods[ORDINAL]?.body == listOf(Insn.IntConstant(case.ordinal), Insn.Plain(Opcodes.IRETURN))) {
            setOf(ORDINAL)
        } else {
            emptySet()
        }
    }

    private fun generatedCanEqual(self: String): List<Insn> =
        listOf(aload(1), Insn.TypeOperand(Opcodes.INSTANCEOF, self), Insn.Plain(Opcodes.IRETURN))

    private fun caseClassOf(shape: ClassShape): CaseClass? =
        if (shape.isScala && CASE_CLASS_SIGNATURE.all { shape.methods[it]?.isStatic == false }) CaseClass(shape) else null

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

    private fun parameterPart(descriptor: String): String = descriptor.substring(1, descriptor.lastIndexOf(')'))

    private fun aload(slot: Int): Insn = Insn.Var(Opcodes.ALOAD, slot)

    private fun areturn(): Insn = Insn.Plain(Opcodes.ARETURN)

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

        data object Other : Insn
    }

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

        fun labelIndex(name: String): Int? = labels[name]

        fun load(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && slots[name] == it.slot } }
        }

        fun store(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && it.slot >= firstLocal && bind(slots, name, it.slot) } }
        }

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

        fun either(
            first: Match.() -> Unit,
            second: Match.() -> Unit,
        ) {
            if (optional { first().let { true } } == null) second()
        }
    }
}
