Scala Debugger
==============

A simple, naive standalone debugger for Scala. Goal is to connect to Apache
Spark executors to examine the code as it is executed.

Potential Gotchas
-----------------

- When moving from Mac OS X's IntelliJ to the IntelliJ of Linux Mint, the
  tools.jar of OpenJDK/Oracle JDK was not picked up. I needed to manually open
  up the SDK being used and add the tools.jar of 
  `/usr/lib/jvm/java-7-openjdk-amd64/lib/` to the classpath. This allows me to
  use the Sun-based `com.sun.jdi` package for a Java debugger interface rather
  than C++.

- When using the launching debugger, I noticed that it was creating an address
  of senkbeil.org:RANDOMPORT. _senkbeil.org_ corresponded to my host name.
  Attempting to telnet into the address and port provided resulted in a failed
  connection. Switching my hostname to _locahost_ allowed the main process
  (and telnet) to connect and use the JVM process.

Other Notes
-----------

- After observing IntelliJ's Scala plugin and the Scala IDE, it appears that
  stepping into a frame is not as simple as one would like in Scala. The
  process appears to use a series of hard-coded class and methods names to
  filter out when stepping into the next frame. This is performed using a
  series of step into and out of operations until the next valid location is
  reached.

