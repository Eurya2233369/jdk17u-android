/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package compiler.ciReplay;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Arrays;
import jdk.test.lib.Platform;
import jdk.test.lib.Asserts;
import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class SABase extends CiReplayBase {
    private static final String REPLAY_FILE_COPY = "replay_vm.txt";

    public static void main(String args[]) throws Exception {
        checkSetLimits();
        SABase base = new SABase(args);
        boolean c2 = base.runServer.orElseThrow(() -> new Error("runServer must be set"));
        String[] extra = {};
        if (Platform.isTieredSupported()) {
            if (c2) {
                // Replay produced on first compilation. We want that
                // compilation delayed so profile data is produced.
                extra = new String[] {"-XX:-TieredCompilation"};
            } else {
                extra = new String[] {"-XX:TieredStopAtLevel=1"};
            }
        }
        base.runTest(/* needCoreDump = */ true, extra);
    }

    public SABase(String[] args) {
        super(args);
    }

    @Override
    public void testAction() {
        try {
            Files.move(Paths.get(REPLAY_FILE_NAME), Paths.get(REPLAY_FILE_COPY));
        } catch (IOException ioe) {
            throw new Error("Can't move files: " + ioe, ioe);
        }
        ProcessBuilder pb;
        try {
            pb = ProcessTools.createTestJvm("--add-modules", "jdk.hotspot.agent",
                   "--add-exports=jdk.hotspot.agent/sun.jvm.hotspot=ALL-UNNAMED",
                    "sun.jvm.hotspot.CLHSDB", JDKToolFinder.getTestJDKTool("java"),
                    TEST_CORE_FILE_NAME);
        } catch (Exception e) {
            throw new Error("Can't create process builder: " + e, e);
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException ioe) {
            throw new Error("Can't start child process: " + ioe, ioe);
        }
        OutputStream input = p.getOutputStream();
        String str = "dumpreplaydata -a > " + REPLAY_FILE_NAME + "\nquit\n";
        try {
            input.write(str.getBytes());
            input.flush();
        } catch (IOException ioe) {
            throw new Error("Problem writing process input: " + str, ioe);
        }
        try {
            p.waitFor();
        } catch (InterruptedException ie) {
            throw new Error("Problem waitinig child process: " + ie, ie);
        }
        int exitValue = p.exitValue();
        if (exitValue != 0) {
            String output;
            try {
                output = new OutputAnalyzer(p).getOutput();
            } catch (IOException ioe) {
                throw new Error("Can't get failed CLHSDB process output: " + ioe, ioe);
            }
            throw new AssertionError("CLHSDB wasn't run successfully: " + output);
        }
        File replay = new File(REPLAY_FILE_NAME);
        Asserts.assertTrue(replay.exists() && replay.isFile() && replay.length() > 0,
                "Replay data wasn't generated by SA");
        // other than comment lines, content of 2 files should be identical
        try {
            BufferedReader rep = new BufferedReader(new FileReader(replay));
            BufferedReader repCopy = new BufferedReader(new FileReader(REPLAY_FILE_COPY));
            boolean failure = false;
            while (true) {
                String l1;
                while ((l1 = rep.readLine()) != null) {
                    if (!l1.startsWith("#")) {
                        break;
                    }
                }
                String l2;
                while ((l2 = repCopy.readLine()) != null) {
                    if (!l2.startsWith("#")) {
                        break;
                    }
                }
                if (l1 == null || l2 == null) {
                    if (l1 != null || l2 != null) {
                        System.out.println("Warning: replay files are not equal");
                        System.out.println("1: " + l1);
                        System.out.println("2: " + l2);
                        failure = true;
                    }
                    break;
                }
                if (!l1.equals(l2)) {
                    System.out.println("Warning: replay files are not equal");
                    System.out.println("1: " + l1);
                    System.out.println("2: " + l2);
                    failure = true;
                }
            }
            if (failure) {
                throw new RuntimeException("Warning: replay files are not equal");
            }
        } catch (IOException ioe) {
            throw new Error("Can't read replay files: " + ioe, ioe);
        }
        commonTests();
        runVmTests();
    }

    public static void checkSetLimits() {
        if (!Platform.isWindows()) {
            OutputAnalyzer oa;
            try {
                // first check if setting limit is possible
                oa = ProcessTools.executeProcess("sh", "-c", RUN_SHELL_NO_LIMIT + "ulimit -c");
            } catch (Throwable t) {
                throw new Error("Can't set limits: " + t, t);
            }
            oa.shouldHaveExitValue(0);

            String out = oa.getOutput().trim(); // cut win/*nix newlines
            if (!out.equals("unlimited") && !out.equals("-1")) {
                throw new Error("Unable to set limits");
            }
        }
    }
}

