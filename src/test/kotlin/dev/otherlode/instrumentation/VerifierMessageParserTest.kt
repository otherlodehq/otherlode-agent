package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals

class VerifierMessageParserTest {
    private fun split(location: String) =
        "Bad type on operand stack\nException Details:\n  Location:\n    $location\n  Reason:\n" +
            "    Type integer (current frame, stack[0]) is not assignable to 'java/lang/Object'\n  Current Frame:\n" +
            "    bci: @1\n    flags: { }\n    locals: { }\n    stack: { integer }\n  Bytecode:\n    0000000: 03b0\n"

    private fun placed(name: String) = ParsedVerifierMessage.Placed(name)

    @Test
    fun `the split verifier's message names the class before the first dot of its location`() {
        val message = split("com/example/v/BadType61.m()Ljava/lang/Object; @1: areturn")

        assertEquals(placed("com.example.v.BadType61"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `a nested class keeps its dollar sign`() {
        val message = split("com/example/Outer\$Inner.run()V @0: return")

        assertEquals(placed("com.example.Outer\$Inner"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `a class in the default package has no slash to convert`() {
        assertEquals(placed("BadDefault"), VerifierMessageParser.parse(split("BadDefault.m()V @0: return")))
    }

    @Test
    fun `a legal class name with a space and a comma is read whole`() {
        val message = split("com/example/odd name, with comma.m()V @0: return")

        assertEquals(placed("com.example.odd name, with comma"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `the split verifier's other reasons are read from the same location line`() {
        val message =
            "Expecting a stackmap frame at branch target 5\nException Details:\n  Location:\n    a/B.m()V @1: ifeq\n" +
                "  Reason:\n    Error exists in the stackmap table.\n"

        assertEquals(placed("a.B"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `the old verifier's message names the class before the method`() {
        val message = "(class: com/example/v/BadType49, method: m signature: ()Ljava/lang/Object;) Expecting to find object/array on stack"

        assertEquals(placed("com.example.v.BadType49"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `the old verifier's message names the class before a field or a bare close parenthesis`() {
        assertEquals(placed("a.B"), VerifierMessageParser.parse("(class: a/B, field: f signature: I) Illegal use"))
        assertEquals(placed("a.B"), VerifierMessageParser.parse("(class: a/B) Bad magic"))
    }

    @Test
    fun `a class whose name contains the old format's separator is read as the prefix before it`() {
        val message = "(class: com/example/a, method: b, method: m signature: ()V) Expecting to find object/array on stack"

        assertEquals(placed("com.example.a"), VerifierMessageParser.parse(message))
    }

    @Test
    fun `a null message is ignored`() {
        assertEquals(ParsedVerifierMessage.Ignored, VerifierMessageParser.parse(null))
    }

    @Test
    fun `each message the class-file API builds is ignored`() {
        val messages =
            listOf(
                "null at constant pool index 12 in class Foo",
                "Illegal class name x at constant pool index 3 in class Foo",
                "Illegal field name a.b in class Foo",
                "Illegal method name <foo> in class Foo",
                "Duplicate interface Runnable in class Foo",
                "Duplicate field name f with signature I in class Foo",
                "Duplicate method name m with signature ()V in class Foo",
                "Method <clinit> is not static in class Foo",
                "Interface cannot have a method named <init> in class Foo",
                "Multiple Signature attributes in class Foo",
                "Multiple Code attributes in method Foo::m(int)",
                "Multiple ConstantValue attributes in field Foo.f",
                "Code attribute in native or abstract method Foo::m()",
                "Arguments can't fit into locals in method Foo::m(int,long)",
                "Bad constant value type in field Foo.f",
                "Class is both outer and inner class in class Foo",
                "Conflicting NestHost and NestMembers attributes in class Foo",
                "PermittedSubclasses attribute in final class Foo",
                "Wrong Code attribute length in method Foo::m()",
                "Wrong Signature attribute length in class Foo",
                "Wrong Record attribute length in Record component x of class Foo",
                "Wrong Code attribute length in Code attribute for method Foo::m()",
                "Inference verification is not supported",
                "Bad type in com/example/Foo::m((I)V) @3",
                "Bad return type in com/example/Foo::m(()V) @4 stack: [int]",
                "Method expects a return value in com/example/Foo::m(()V) @1 \n  while assigning int\n  to Object",
                "Bad type in com/example/Foo::m(()V) @2 \n  while assigning (frame A)\n  to (frame B)",
                "Unexpected signature type in com/example/Foo::m(()V)",
            )

        for (message in messages) {
            assertEquals(ParsedVerifierMessage.Ignored, VerifierMessageParser.parse(message), message)
        }
    }

    @Test
    fun `a message in no read format is unplaced`() {
        val messages =
            listOf(
                "Could not link verifier",
                "",
                "payment failed for order 42",
                "java.lang.IllegalStateException: boom",
                "Exception Details:\n  Location:\n    a/B.m()V\n",
                "Bad type on operand stack\nException Details:\n  Reason:\n    something\n",
                "(class: )",
                "Duplicate method name m in Foo",
            )

        for (message in messages) {
            assertEquals(ParsedVerifierMessage.Unplaced, VerifierMessageParser.parse(message), message)
        }
    }
}
