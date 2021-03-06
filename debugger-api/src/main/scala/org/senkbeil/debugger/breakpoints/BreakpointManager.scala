package org.senkbeil.debugger.breakpoints

import java.util.concurrent.ConcurrentHashMap

import org.senkbeil.debugger.classes.ClassManager
import org.senkbeil.debugger.jdi.JDIHelperMethods
import org.senkbeil.utils.LogLike
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.EventRequest

import scala.collection.mutable
import scala.collection.JavaConverters._

/**
 * Represents the manager for breakpoint requests.
 *
 * @param _virtualMachine The virtual machine whose breakpoint requests to
 *                        manage
 * @param _classManager The class manager associated with the virtual machine,
 *                      used to retrieve location information
 */
class BreakpointManager(
  protected val _virtualMachine: VirtualMachine,
  private val _classManager: ClassManager
) extends JDIHelperMethods with LogLike {
  private val eventRequestManager = _virtualMachine.eventRequestManager()

  type BreakpointBundleKey = (String, Int) // Class Name, Line Number
  private var lineBreakpoints = Map[BreakpointBundleKey, BreakpointBundle]()

  private case class BreakpointInfo(
    fileName: String, lineNumber: Int, enabled: Boolean, suspendPolicy: Int)
  private val pendingLineBreakpoints: mutable.Map[String, Seq[BreakpointInfo]] =
    new ConcurrentHashMap[String, Seq[BreakpointInfo]]().asScala

  /**
   * Retrieves the list of breakpoints contained by this manager.
   *
   * @return The collection of breakpoints in the form of
   *         (class name, line number)
   */
  def breakpointList: Seq[BreakpointBundleKey] = lineBreakpoints.keys.toSeq

  /**
   * Processes pending breakpoint requests for the specified file name.
   *
   * @param fileName The name of the file whose pending breakpoints to process
   *
   * @return True if all pending breakpoints for the file were successfully
   *         added, otherwise false
   */
  def processPendingBreakpoints(fileName: String): Boolean = {
    def tryBreakpoint(breakpointInfo: BreakpointInfo) = setLineBreakpoint(
      fileName          = breakpointInfo.fileName,
      lineNumber        = breakpointInfo.lineNumber,
      enabled           = breakpointInfo.enabled,
      suspendPolicy     = breakpointInfo.suspendPolicy,
      setPendingIfFail  = true
    )

    // Process all breakpoints
    pendingLineBreakpoints.remove(fileName).foreach(_.foreach(tryBreakpoint))

    // Indicate whether or not we successfully added all of the breakpoints
    !pendingLineBreakpoints.contains(fileName)
  }

  /**
   * Creates and enables a breakpoint on the specified line of the class.
   *
   * @param fileName The name of the file to set a breakpoint
   * @param lineNumber The number of the line to break
   * @param enabled If true, enables the breakpoint (default is true)
   * @param suspendPolicy Indicates the policy for suspending when the
   *                      breakpoint is hit (default is all threads)
   * @param setPendingIfFail If true, will add the attempted breakpoint to a
   *                         collection of pending breakpoints
   *
   * @return True if successfully added breakpoints, otherwise false
   */
  def setLineBreakpoint(
    fileName: String,
    lineNumber: Int,
    enabled: Boolean = true,
    suspendPolicy: Int = EventRequest.SUSPEND_ALL,
    setPendingIfFail: Boolean = true
  ): Boolean = {
    val result = setLineBreakpoint(fileName, lineNumber, enabled, suspendPolicy)

    // Add the attempt to our list for processing later
    if (!result && setPendingIfFail) pendingLineBreakpoints.synchronized {
      val oldPendingBreakpoints =
        pendingLineBreakpoints.getOrElse(fileName, Nil)
      val newPendingBreakpoint =
        BreakpointInfo(fileName, lineNumber, enabled, suspendPolicy)

      pendingLineBreakpoints.put(
        fileName, oldPendingBreakpoints :+ newPendingBreakpoint)
    }

    result
  }

  /**
   * Creates and enables a breakpoint on the specified line of the class.
   *
   * @param fileName The name of the file to set a breakpoint
   * @param lineNumber The number of the line to break
   * @param enabled If true, enables the breakpoint (default is true)
   * @param suspendPolicy Indicates the policy for suspending when the
   *                      breakpoint is hit (default is all threads)
   *
   * @return True if successfully added breakpoints, otherwise false
   */
  private def setLineBreakpoint(
    fileName: String,
    lineNumber: Int,
    enabled: Boolean,
    suspendPolicy: Int
  ): Boolean = {
    // Retrieve the available locations for the specified line
    val locations = _classManager
      .linesAndLocationsForFile(fileName)
      .flatMap(_.get(lineNumber))
      .getOrElse(Nil)

    // Exit early if no locations are available
    if (locations.isEmpty) return false

    // TODO: Investigate what level of suspension we need (the entire VM?) and
    //       what code within this block actually needs the suspension
    // Create and enable breakpoints for all underlying locations
    val result = suspendVirtualMachineAndExecute {
      // Our key is using the class name and line number relevant to the
      // line breakpoint
      val key: BreakpointBundleKey = (fileName, lineNumber)

      // Build our bundle of breakpoints
      // TODO: Need to undo this if creating a request failed
      val breakpointBundle = new BreakpointBundle(
        locations.map(eventRequestManager.createBreakpointRequest)
      )

      // Set relevant information over all breakpoints
      breakpointBundle.setSuspendPolicy(suspendPolicy)
      breakpointBundle.setEnabled(enabled)

      // Add the bundle to our list of line breakpoints
      lineBreakpoints += key -> breakpointBundle
    }

    // Log the error if one occurred
    if (result.isFailure) logger.throwable(result.failed.get)

    // Log if successful
    if (result.isSuccess)
      logger.trace(s"Added breakpoint $fileName:$lineNumber")

    result.isSuccess
  }

  /**
   * Determines whether or not the breakpoint for the specific file's line.
   *
   * @param fileName The name of the file whose line to reference
   * @param lineNumber The number of the line to check for a breakpoint
   *
   * @return True if a breakpoint exists, otherwise false
   */
  def hasLineBreakpoint(fileName: String, lineNumber: Int): Boolean =
    lineBreakpoints.contains((fileName, lineNumber))

  /**
   * Returns the bundle of breakpoints representing the breakpoint for the
   * specified line.
   *
   * @param fileName The name of the file whose line to reference
   * @param lineNumber The number of the line to check for breakpoints
   *
   * @return Some bundle of breakpoints for the specified line, or None if
   *         the specified line has no breakpoints
   */
  def getLineBreakpoint(
    fileName: String,
    lineNumber: Int
  ): Option[BreakpointBundle] = lineBreakpoints.get((fileName, lineNumber))

  /**
   * Removes the breakpoint on the specified line of the file.
   *
   * @param fileName The name of the file to remove the breakpoint
   * @param lineNumber The number of the line to break
   *
   * @return True if successfully removed breakpoint, otherwise false
   */
  def removeLineBreakpoint(fileName: String, lineNumber: Int): Boolean = {
    // Remove breakpoints for all underlying locations
    val result = suspendVirtualMachineAndExecute {
      val key: BreakpointBundleKey = (fileName, lineNumber)

      val breakpointBundleToRemove = lineBreakpoints(key)

      lineBreakpoints -= key

      breakpointBundleToRemove.foreach(eventRequestManager.deleteEventRequest)
    }

    // Log the error if one occurred
    if (result.isFailure) logger.throwable(result.failed.get)

    // Log if successful
    if (result.isSuccess)
      logger.trace(s"Removed breakpoint $fileName:$lineNumber")

    result.isSuccess
  }
}
