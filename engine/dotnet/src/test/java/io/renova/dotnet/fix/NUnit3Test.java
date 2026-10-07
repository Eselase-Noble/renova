package io.renova.dotnet.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NUnit3Test {

    @Test
    void anExpectedExceptionBecomesAssertThrowsAroundTheBody() {
        String before = """
                using NUnit.Framework;

                namespace Tests
                {
                    [TestFixture]
                    public class MachineFixture
                    {
                        [Test, ExpectedException(typeof(InvalidOperationException))]
                        public void FiringAnUnknownTriggerThrows()
                        {
                            var sm = new Machine("a } in a string");
                            sm.Fire(Trigger.X); // and a } in a comment
                        }

                        [Test]
                        [ExpectedException(typeof(ArgumentException))]
                        public void NullIsRejected()
                        {
                            new Machine(null);
                        }

                        [TestFixtureSetUp]
                        public void Once() { }

                        [Test, ExpectedException(typeof(FormatException), ExpectedMessage = "bad")]
                        public void WithAMessage()
                        {
                            Parse("x");
                        }
                    }
                }
                """;
        assertThat(NUnit3.upgrade(before)).isEqualTo("""
                using NUnit.Framework;

                namespace Tests
                {
                    [TestFixture]
                    public class MachineFixture
                    {
                        [Test]
                        public void FiringAnUnknownTriggerThrows()
                        {
                            Assert.Throws<InvalidOperationException>(() =>
                            {
                                var sm = new Machine("a } in a string");
                                sm.Fire(Trigger.X); // and a } in a comment
                            });
                        }

                        [Test]
                        public void NullIsRejected()
                        {
                            Assert.Throws<ArgumentException>(() =>
                            {
                                new Machine(null);
                            });
                        }

                        [OneTimeSetUp]
                        public void Once() { }

                        [Test, ExpectedException(typeof(FormatException), ExpectedMessage = "bad")]
                        public void WithAMessage()
                        {
                            Parse("x");
                        }
                    }
                }
                """);
    }

    @Test
    void renamedAssertionsAndIgnoreAreRewritten() {
        assertThat(NUnit3.upgrade("Assert.IsNullOrEmpty(name);\n[Ignore]\nAssert.IsNotNullOrEmpty(Get(1));\n")).isEqualTo(
                "Assert.That(name, Is.Null.Or.Empty);\n[Ignore(\"Ignored in the original tests\")]\nAssert.That(Get(1), Is.Not.Null.And.Not.Empty);\n");
        String untouched = "[Test]\npublic void Fine() { Assert.AreEqual(1, 1); }\n";
        assertThat(NUnit3.upgrade(untouched)).isEqualTo(untouched);
    }
}
