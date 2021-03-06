package org.senkbeil.debugger.virtualmachines

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span, Milliseconds}
import org.scalatest.{ParallelTestExecution, FunSpec, Matchers}
import test.{TestUtilities, VirtualMachineFixtures}

class ScalaVirtualMachineIntegrationSpec extends FunSpec with Matchers
  with ParallelTestExecution with VirtualMachineFixtures
  with TestUtilities with Eventually
{
  implicit override val patienceConfig = PatienceConfig(
    timeout = scaled(Span(2, Seconds)),
    interval = scaled(Span(5, Milliseconds))
  )

  describe("ScalaVirtualMachine") {
    it("should return the class name of a Scala main method entrypoint") {
      val testClass = "org.senkbeil.test.misc.MainUsingMethod"

      withVirtualMachine(testClass, suspend = false) { (_, scalaVirtualMachine) =>
        val expected = testClass

        // NOTE: This is not available until AFTER we have resumed from the
        //       start event (as the main method is not yet loaded)
        eventually {
          val actual = scalaVirtualMachine.mainClassName
          actual should be(expected)
        }
      }
    }

    it("should return the class name of a Scala App entrypoint") {
      val testClass = "org.senkbeil.test.misc.MainUsingApp"

      withVirtualMachine(testClass, suspend = false) { (_, scalaVirtualMachine) =>
        val expected = testClass

        // NOTE: This is not available until AFTER we have resumed from the
        //       start event (as the main method is not yet loaded)
        eventually {
          val actual = scalaVirtualMachine.mainClassName
          actual should be(expected)
        }
      }
    }

    it("should return the arguments provided to the virtual machine") {
      val testClass = "org.senkbeil.test.misc.MainUsingApp"
      val testArguments = Seq("a", "b", "c")

      withVirtualMachine(testClass, testArguments, suspend = false) { (_, scalaVirtualMachine) =>

        val expected = testArguments

        // NOTE: This is not available until AFTER we have resumed from the
        //       start event (as the main method is not yet loaded)
        eventually {
          val actual = scalaVirtualMachine.commandLineArguments
          actual should contain theSameElementsInOrderAs expected
        }
      }
    }

    it("should return the breakpointable line numbers for the file") {
      val testClass = "org.senkbeil.test.misc.AvailableLines"

      withVirtualMachine(testClass, suspend = false) { (_, scalaVirtualMachine) =>
        // NOTE: This fails with Scala 2.11 as there is no line 11 but there
        //       is a line 72 (is this influenced by how Scala translates to
        //       Java?)
        val expected = Seq(
          11, 12, 13, 14, 15, 16, 20, 21, 22, 26, 27, 28, 32, 34, 35, 37, 39,
          40, 41, 42, 45, 46, 47, 50, 52, 53, 57, 58, 59, 60, 63, 65
        )

        val file = scalaClassStringToFileString(testClass)

        // There is some delay while receiving the Java classes that make up
        // our file, so must wait for enough responses to get all of our lines
        eventually {
          val actual = scalaVirtualMachine.availableLinesForFile(file).get
          actual should contain theSameElementsInOrderAs expected
        }
      }
    }
  }
}
