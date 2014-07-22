/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

THIS CODE IS PROVIDED ON AN *AS IS* BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED,
INCLUDING WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE, FITNESS FOR A PARTICULAR PURPOSE,
MERCHANTABLITY OR NON-INFRINGEMENT.

See the Apache 2 License for the specific language governing permissions and limitations under the License.
*/

/*
This code took the Socks4a logic from SocksProxyClientConnOperator in NetCipher which we then modified
to meet our needs. That original code was licensed as:

This file contains the license for Orlib, a free software project to
provide anonymity on the Internet from a Google Android smartphone.

For more information about Orlib, see https://guardianproject.info/

If you got this file as a part of a larger bundle, there may be other
license terms that you should be aware of.
===============================================================================
Orlib is distributed under this license (aka the 3-clause BSD license)

Copyright (c) 2009-2010, Nathan Freitas, The Guardian Project

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.

    * Neither the names of the copyright owners nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*****
Orlib contains a binary distribution of the JSocks library:
http://code.google.com/p/jsocks-mirror/
which is licensed under the GNU Lesser General Public License:
http://www.gnu.org/licenses/lgpl.html

*****

 */

package com.msopentech.thali.toronionproxy;

import com.msopentech.thali.local.toronionproxy.TorOnionProxyTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TorOnionProxySmokeTest extends TorOnionProxyTestCase {
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 60000;
    private static final int READ_TIMEOUT_MILLISECONDS = 60000;
    private static final int TOTAL_MINUTES_FOR_TEST_TO_RUN = 3;
    private static final Logger LOG = LoggerFactory.getLogger(TorOnionProxySmokeTest.class);

    /**
     * Start two TorOPs, one for a hidden service and one for a client. Have the hidden service op stop and start
     * and see if the client can get connected again.
     * @throws IOException
     * @throws InterruptedException
     */
    public void testHiddenServiceRecycleTime() throws IOException, InterruptedException {
        String hiddenServiceManagerDirectoryName = "hiddenservicemanager";
        String clientManagerDirectoryName = "clientmanager";
        OnionProxyManager hiddenServiceManager = null, clientManager = null;
        try {
            deleteTorWorkingDirectory(hiddenServiceManagerDirectoryName);
            hiddenServiceManager = getOnionProxyManager(hiddenServiceManagerDirectoryName);
            assertTrue(hiddenServiceManager.startWithRepeat(30, 5));

            deleteTorWorkingDirectory(clientManagerDirectoryName);
            clientManager = getOnionProxyManager(clientManagerDirectoryName);
            assertTrue(clientManager.startWithRepeat(30, 5));

            String onionAddress = runHiddenServiceTest(hiddenServiceManager, clientManager);

            // Now take down the hidden service manager and bring it back up with a new descriptor but the
            // same address
            hiddenServiceManager.stop();
            hiddenServiceManager.startWithRepeat(30, 5);
            // It's possible that one of our deletes could have nuked the hidden service directory
            // in which case we would actually be testing against a new hidden service which would
            // remove the point of this test. So we check that they are the same.
            assertEquals(runHiddenServiceTest(hiddenServiceManager, clientManager), onionAddress);
        } finally {
            if (hiddenServiceManager != null) {
                hiddenServiceManager.stop();
            }
            if (clientManager != null) {
                clientManager.stop();
            }
        }
    }

    private String runHiddenServiceTest(OnionProxyManager hiddenServiceManager, OnionProxyManager clientManager)
            throws IOException, InterruptedException {
        int localPort = 9343;
        int hiddenServicePort = 9344;
        String onionAddress = hiddenServiceManager.publishHiddenService(hiddenServicePort, localPort);
        LOG.info("onionAddress for test hidden service is: " + onionAddress);

        byte[] testBytes = new byte[] { 0x01, 0x02, 0x03, 0x05};

        CountDownLatch countDownLatch = receiveExpectedBytes(testBytes, localPort);

        Socket clientSocket =
                getClientSocket(onionAddress, hiddenServicePort, clientManager.getIPv4LocalHostSocksPort());

        DataOutputStream clientOutputStream = new DataOutputStream(clientSocket.getOutputStream());
        clientOutputStream.write(testBytes);
        clientOutputStream.flush();
        assertTrue(countDownLatch.await(TOTAL_MINUTES_FOR_TEST_TO_RUN, TimeUnit.MINUTES));
        return onionAddress;
    }

    /**
     * It can take awhile for a new hidden service to get registered
     * @param onionAddress DNS style hidden service address of from x.onion
     * @param hiddenServicePort Hidden service's port
     * @param socksPort Socks port that Tor OP is listening on
     * @return A socket via Tor that is connected to the hidden service
     */
    private Socket getClientSocket(String onionAddress, int hiddenServicePort, int socksPort)
            throws InterruptedException {
        long timeToExit = Calendar.getInstance().getTimeInMillis() + TOTAL_MINUTES_FOR_TEST_TO_RUN*60*1000;
        Socket clientSocket = null;
        while (Calendar.getInstance().getTimeInMillis() < timeToExit && clientSocket == null) {
            try {
                clientSocket = socks4aSocketConnection(onionAddress, hiddenServicePort, "127.0.0.1", socksPort);
            } catch (IOException e) {
                LOG.error("attempt to set clientSocket failed, will retry", e);
                    Thread.sleep(5000, 0);
            }
        }

        if (clientSocket == null) {
            throw new RuntimeException("Could not set clientSocket");
        }

        return clientSocket;
    }

    private CountDownLatch receiveExpectedBytes(final byte[] expectedBytes, int localPort) throws IOException {
        final ServerSocket serverSocket = new ServerSocket(localPort);
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        new Thread(new Runnable() {
            public void run() {
                Socket receivedSocket = null;
                try {
                    receivedSocket = serverSocket.accept();
                    LOG.info("Received incoming connection");
                    DataInputStream dataInputStream = new DataInputStream(receivedSocket.getInputStream());
                    for(byte nextByte : expectedBytes) {
                        byte receivedByte = dataInputStream.readByte();
                        if (nextByte != receivedByte) {
                            LOG.error("Received " + receivedByte + ", but expected " + nextByte);
                            return;
                        } else {
                            LOG.info("Received " + receivedByte);
                        }
                    }
                    countDownLatch.countDown();
                } catch(IOException e) {
                    LOG.error("Test Failed", e);
                } finally {
                    // I suddenly am getting IncompatibleClassChangeError: interface no implemented when
                    // calling these functions. I saw a random Internet claim (therefore it must be true!)
                    // that closeable is only supported on sockets in API 19 but I'm compiling with 19 (although
                    // targeting 18). To make things even weirder, this test passed before!!! I am soooo confused.
                    try {
                        if (receivedSocket != null) {
                            receivedSocket.close();
                            LOG.info("Close of receiveExpectedBytes worked");
                        }
                        serverSocket.close();
                        LOG.info("Server socket is closed");
                    } catch (IOException e) {
                        LOG.error("Close failed!", e);
                    }
                }
            }
        }).start();

        return countDownLatch;
    }

    private void deleteTorWorkingDirectory(String workingSubDirectoryName) {
        OnionProxyContext onionProxyContext = getOnionProxyContext(workingSubDirectoryName);
        File torWorkingDirectory = onionProxyContext.getWorkingDirectory();
        FileUtilities.recursiveFileDelete(torWorkingDirectory);
        if (torWorkingDirectory.mkdirs() == false) {
            throw new RuntimeException("couldn't create Tor Working Directory after deleting it.");
        }
    }

    private Socket socks4aSocketConnection(String networkHost, int networkPort, String socksHost, int socksPort)
            throws IOException {
        // Perform explicit SOCKS4a connection request. SOCKS4a supports remote host name resolution
        // (i.e., Tor resolves the hostname, which may be an onion address).
        // The Android (Apache Harmony) Socket class appears to support only SOCKS4 and throws an
        // exception on an address created using INetAddress.createUnresolved() -- so the typical
        // technique for using Java SOCKS4a/5 doesn't appear to work on Android:
        // https://android.googlesource.com/platform/libcore/+/master/luni/src/main/java/java/net/PlainSocketImpl.java
        // See also: http://www.mit.edu/~foley/TinFoil/src/tinfoil/TorLib.java, for a similar implementation

        // From http://en.wikipedia.org/wiki/SOCKS#SOCKS4a:
        //
        // field 1: SOCKS version number, 1 byte, must be 0x04 for this version
        // field 2: command code, 1 byte:
        //     0x01 = establish a TCP/IP stream connection
        //     0x02 = establish a TCP/IP port binding
        // field 3: network byte order port number, 2 bytes
        // field 4: deliberate invalid IP address, 4 bytes, first three must be 0x00 and the last one must not be 0x00
        // field 5: the user ID string, variable length, terminated with a null (0x00)
        // field 6: the domain name of the host we want to contact, variable length, terminated with a null (0x00)

        Socket socket = new Socket();
        socket.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        SocketAddress socksAddress = new InetSocketAddress(socksHost, socksPort);
        socket.connect(socksAddress, CONNECT_TIMEOUT_MILLISECONDS);

        DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
        outputStream.write((byte)0x04);
        outputStream.write((byte)0x01);
        outputStream.writeShort((short)networkPort);
        outputStream.writeInt(0x01);
        outputStream.write((byte)0x00);
        outputStream.write(networkHost.getBytes());
        outputStream.write((byte)0x00);

        DataInputStream inputStream = new DataInputStream(socket.getInputStream());
        byte firstByte = inputStream.readByte();
        byte secondByte = inputStream.readByte();
        if (firstByte != (byte)0x00 || secondByte != (byte)0x5a) {
            socket.close();
            throw new IOException("SOCKS4a connect failed, got " + firstByte + " - " + secondByte +
                    ", but expected 0x00 - 0x5a");
        }
        LOG.info("We got a socket!");
        inputStream.readShort();
        inputStream.readInt();
        return socket;
    }
}