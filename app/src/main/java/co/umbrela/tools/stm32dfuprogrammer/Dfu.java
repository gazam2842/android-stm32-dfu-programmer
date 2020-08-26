/*
 * Copyright 2015 Umbrela Smart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.umbrela.tools.stm32dfuprogrammer;

import android.os.Environment;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

public class Dfu {

    private final static int USB_DIR_OUT = 0;
    private final static int USB_DIR_IN = 128;       //0x80
    private final static int DFU_RequestType = 0x21;  // '2' => Class request ; '1' => to interface

    private final static int STATE_IDLE = 0x00;
    private final static int STATE_DETACH = 0x01;
    private final static int STATE_DFU_IDLE = 0x02;
    private final static int STATE_DFU_DOWNLOAD_SYNC = 0x03;
    private final static int STATE_DFU_DOWNLOAD_BUSY = 0x04;
    private final static int STATE_DFU_DOWNLOAD_IDLE = 0x05;
    private final static int STATE_DFU_MANIFEST_SYNC = 0x06;
    private final static int STATE_DFU_MANIFEST = 0x07;
    private final static int STATE_DFU_MANIFEST_WAIT_RESET = 0x08;
    private final static int STATE_DFU_UPLOAD_IDLE = 0x09;
    private final static int STATE_DFU_ERROR = 0x0A;
    private final static int STATE_DFU_UPLOAD_SYNC = 0x91;
    private final static int STATE_DFU_UPLOAD_BUSY = 0x92;

    // DFU Commands, request ID code when using controlTransfers
    private final static int DFU_DETACH = 0x00;
    private final static int DFU_DNLOAD = 0x01;
    private final static int DFU_UPLOAD = 0x02;
    private final static int DFU_GETSTATUS = 0x03;
    private final static int DFU_CLRSTATUS = 0x04;
    private final static int DFU_GETSTATE = 0x05;
    private final static int DFU_ABORT = 0x06;

    Usb mUsb;
    TextView tv;
    int mDeviceVID;
    int mDevicePID;
    int mDeviceVersion;  //STM bootloader version
    DfuFile mDfuFile;

    public Dfu(int usbVendorId, int usbProductId) {
        mDeviceVID = usbVendorId;
        mDevicePID = usbProductId;

        mDfuFile = new DfuFile();
    }

    public void setmUsb(Usb usb) {
        mUsb = usb;
    }

    public void setTextView(TextView tv) {
        this.tv = tv;
    }

    public void setDeviceVersion(int deviceVersion) {
        mDeviceVersion = deviceVersion;
    }

    public  int ONE_PAGE = 4*1024;
    public void massErase() {

        // check if usb device is active
        if (mUsb == null || !mUsb.isConnected()) {
            tv.setText("No device connected");
            return;
        }

        DFU_Status dfuStatus = new DFU_Status();
        long startTime = System.currentTimeMillis();  // note current time

        try {
            do {
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);

            if (isDeviceProtected()) {
                removeReadProtection();
                tv.setText("Read Protection removed. Device resets...Wait until it re-enumerates ");
                return;
            }

            massEraseCommand();                 // sent erase command request
            getStatus(dfuStatus);                // initiate erase command, returns 'download busy' even if invalid address or ROP
            int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
            do {
            /* wait specified time before next getStatus call */
                Thread.sleep(pollingTime);
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);
            tv.setText("Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            tv.setText(e.toString());
        }
        return;
    }

    public void partialEraese() {

        // check if usb device is active
        if (mUsb == null || !mUsb.isConnected()) {
            tv.setText("No device connected");
            return;
        }

        DFU_Status dfuStatus = new DFU_Status();
        long startTime = System.currentTimeMillis();  // note current time

        try {
            do {
                clearStatus();
                getStatus(dfuStatus);
            } while (dfuStatus.bState != STATE_DFU_IDLE);

            if (isDeviceProtected()) {
                removeReadProtection();
                tv.setText("Read Protection removed. Device resets...Wait until it re-enumerates ");
                return;
            }

            //massEraseCommand();                 // sent erase command request
            openFile();
            verifyFile();
            int address = mDfuFile.fwStartAddress;
            tv.setText("start address : " + Integer.toHexString(address) + "size " + mDfuFile.fwLength+"\n");

            for (int i = 0; i <= (mDfuFile.fwLength / ONE_PAGE); i++) {
                onePageEraseCommand(address);
                getStatus(dfuStatus); // initiate erase command, returns 'download busy' even if invalid address or ROP
                int pollingTime = dfuStatus.bwPollTimeout; // note requested waiting time
                do {
                    /* wait specified time before next getStatus call */
                    Thread.sleep(pollingTime);
                    clearStatus();
                    getStatus(dfuStatus);
                } while (dfuStatus.bState != STATE_DFU_IDLE);
                tv.append("erase 0x" + Integer.toHexString(address) +"\n");
                address = address + ONE_PAGE;
            }

        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            tv.setText(e.toString());
        }
        return;
    }

    public void fastOperations() {

        // check if usb device is active
        if (mUsb == null || !mUsb.isConnected()) {
            tv.setText("No device connected");
            return;
        }

        DFU_Status dfuStatus = new DFU_Status();
        byte[] configBytes = new byte[4];

        try {

            if (isDeviceProtected()) {
                tv.setText("Device is Read-Protected...First Mass Erase");
                return;
            }

            readDeviceFeature(configBytes);

            if (configBytes[0] != 0x03) {
                configBytes[0] = 0x03;

                download(configBytes, configBytes.length, 2);
                getStatus(dfuStatus);

                getStatus(dfuStatus);
                while (dfuStatus.bState != STATE_DFU_IDLE) {
                    clearStatus();
                    getStatus(dfuStatus);
                }
                tv.setText("Fast Operations set (Parallelism x32)");
            } else {
                tv.setText("Fast Operations was already set (Parallelism x32)");
            }

        } catch (Exception e) {
            tv.setText(e.toString());
        }
    }

    public void program() {

        if (mUsb == null || !mUsb.isConnected()) {
            tv.setText("No device connected");
            return;
        }
        try {
            if (isDeviceProtected()) {
                tv.setText("Device is Read-Protected...First Mass Erase");
                return;
            }

            openFile();
            verifyFile();
            checkCompatibility();
            tv.setText("File Path: " + mDfuFile.filePath + "\n");
            tv.append("File Size: " + mDfuFile.buffer.length + " Bytes \n");
            tv.append("ElementAddress: 0x" + Integer.toHexString(mDfuFile.fwStartAddress));
            tv.append("\tElementSize: " + mDfuFile.fwLength + " Bytes\n");
            tv.append("Start writing file in blocks of " + mDfuFile.maxBlockSize + " Bytes \n");

            long startTime = System.currentTimeMillis();
            writeImage();
            tv.append("Programming completed in " + (System.currentTimeMillis() - startTime) + " ms\n");

            //tv.append("Detached and starting application");
            // detach(mDfuFile.fwStartAddress);
        } catch (Exception e) {
            e.printStackTrace();
            tv.append(e.toString());
        }
       // mUsb.release();
    }

    public void verify() {
        if (mUsb == null || !mUsb.isConnected()) {
            tv.setText("No device connected");
            return;
        }

        try {
            if( isDeviceProtected()){
                tv.setText("Device is Read-Protected...First Mass Erase");
                return;
            }

            if (mDfuFile.filePath == null) {
                openFile();
                verifyFile();
                checkCompatibility();
            }

            byte[] deviceFirmware = new byte[mDfuFile.fwLength];
            readImage(deviceFirmware);

            // create byte buffer and compare content
            ByteBuffer fileFw = ByteBuffer.wrap(mDfuFile.buffer, mDfuFile.fwOffset, mDfuFile.fwLength);    // set offset and limit of firmware
            ByteBuffer deviceFw = ByteBuffer.wrap(deviceFirmware);    // wrap whole array

            if (fileFw.equals(deviceFw) ) {        // compares type, length, content
                tv.setText("device firmware equals file firmware");
            } else {
                tv.setText("device firmware does not equals file firmware");
            }
        } catch (Exception e) {
            e.printStackTrace();
            tv.setText(e.toString());
        }
    }

    private void removeReadProtection() throws Exception {
        DFU_Status dfuStatus = new DFU_Status();
        unProtectCommand();
        getStatus(dfuStatus);
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("Failed to execute unprotect command");
        }
        mUsb.release();
    }

    private void readDeviceFeature(byte[] configBytes) throws Exception {

        DFU_Status dfuStatus = new DFU_Status();

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(0xFFFF0000);
        getStatus(dfuStatus);

        getStatus(dfuStatus);
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Fast Operations not supported");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }

        upload(configBytes, configBytes.length, 2);
        getStatus(dfuStatus);

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
    }

    private void writeImage() throws Exception {

        int address = mDfuFile.fwStartAddress;  // flash start address
        int BufferOffset = mDfuFile.fwOffset;   // index offset of buffer
        int blockSize = mDfuFile.maxBlockSize;   // max block size
        byte[] Block = new byte[blockSize];
        int NumOfBlocks = mDfuFile.fwLength / blockSize;
        int blockNum;

        for (blockNum = 0; blockNum < NumOfBlocks; blockNum++) {
            System.arraycopy(mDfuFile.buffer, (blockNum * blockSize) + BufferOffset, Block, 0, blockSize);
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
        // check if last block is partial
        int remainder = mDfuFile.fwLength - (blockNum * blockSize);
        if (remainder > 0) {
            System.arraycopy(mDfuFile.buffer, (blockNum * blockSize) + BufferOffset, Block, 0, remainder);
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < Block.length) {
                Block[remainder++] = (byte) 0xFF;
            }
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
    }


    private void readImage(byte[] deviceFw) throws Exception {

        DFU_Status dfuStatus = new DFU_Status();
        int maxBlockSize = mDfuFile.maxBlockSize;
        int startAddress = mDfuFile.fwStartAddress;
        byte[] block = new byte[maxBlockSize];
        int nBlock;
        int remLength = deviceFw.length;
        int numOfBlocks = remLength / maxBlockSize;

        do{
            clearStatus();
            getStatus(dfuStatus);
        }while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(startAddress);
        getStatus(dfuStatus);   // to execute
        getStatus(dfuStatus);   //to verify
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Start address not supported");
        }


        // will read full and last partial blocks ( NOTE: last partial block will be read with maxkblocksize)
        for (nBlock = 0; nBlock <= numOfBlocks; nBlock++) {

            while (dfuStatus.bState != STATE_DFU_IDLE) {        // todo if fails, maybe stop reading
                clearStatus();
                getStatus(dfuStatus);
            }
            upload(block, maxBlockSize, nBlock + 2);
            getStatus(dfuStatus);

            if (remLength >= maxBlockSize) {
                remLength -= maxBlockSize;
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), maxBlockSize);
            } else {
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), remLength);
            }
        }
    }

    private void openFile() throws Exception {

        File extDownload;
        String myFilePath = null;
        String myFileName = null;
        FileInputStream fileInputStream;
        File myFile;

        if (Environment.getExternalStorageState() != null)  // todo not sure if this works
        {
            extDownload = new File(Environment.getExternalStorageDirectory() + "/Download/");

            if (extDownload.exists()) {
                String[] files = extDownload.list();
                // todo support multiple dfu files in dir
                if (files.length > 0) {   // will select first dfu file found in dir
                    for (String file : files) {
                        if (file.endsWith(".dfu")) {
                            myFilePath = extDownload.toString();
                            myFileName = file;
                            break;
                        }
                    }
                }
            }
        }
        if (myFileName == null) throw new Exception("No .dfu file found in Download Folder");

        myFile = new File(myFilePath + "/" + myFileName);
        mDfuFile.filePath = myFile.toString();
        mDfuFile.buffer = new byte[(int) myFile.length()];

        //convert file into byte array
        fileInputStream = new FileInputStream(myFile);
        fileInputStream.read(mDfuFile.buffer);
        fileInputStream.close();
    }

    private void verifyFile() throws Exception {

        // todo for now i expect the file to be not corrupted

        int Length = mDfuFile.buffer.length;

        int crcIndex = Length - 4;
        int crc = 0;
        crc |= mDfuFile.buffer[crcIndex++] & 0xFF;
        crc |= (mDfuFile.buffer[crcIndex++] & 0xFF) << 8;
        crc |= (mDfuFile.buffer[crcIndex++] & 0xFF) << 16;
        crc |= (mDfuFile.buffer[crcIndex] & 0xFF) << 24;
        // do crc check
        if (crc != calculateCRC(mDfuFile.buffer)) {
            throw new Exception("CRC Failed");
        }

        // Check the prefix
        String prefix = new String(mDfuFile.buffer, 0, 5);
        if (prefix.compareTo("DfuSe") != 0) {
            throw new Exception("File signature error");
        }

        // check dfuSe Version
        if (mDfuFile.buffer[5] != 1) {
            throw new Exception("DFU file version must be 1");
        }

        // Check the suffix
        String suffix = new String(mDfuFile.buffer, Length - 8, 3);
        if (suffix.compareTo("UFD") != 0) {
            throw new Exception("File suffix error");
        }
        if ((mDfuFile.buffer[Length - 5] != 16) || (mDfuFile.buffer[Length - 10] != 0x1A) || (mDfuFile.buffer[Length - 9] != 0x01)) {
            throw new Exception("File number error");
        }

        // Now check the target prefix, we assume there is only one target in the file
        String target = new String(mDfuFile.buffer, 11, 6);
        if (target.compareTo("Target") != 0) {
            throw new Exception("Target signature error");
        }

        // Get Element Flash start address and size
        mDfuFile.fwStartAddress = mDfuFile.buffer[285] & 0xFF;
        mDfuFile.fwStartAddress |= (mDfuFile.buffer[286] & 0xFF) << 8;
        mDfuFile.fwStartAddress |= (mDfuFile.buffer[287] & 0xFF) << 16;
        mDfuFile.fwStartAddress |= (mDfuFile.buffer[288] & 0xFF) << 24;

        mDfuFile.fwLength = mDfuFile.buffer[289] & 0xFF;
        mDfuFile.fwLength |= (mDfuFile.buffer[290] & 0xFF) << 8;
        mDfuFile.fwLength |= (mDfuFile.buffer[291] & 0xFF) << 16;
        mDfuFile.fwLength |= (mDfuFile.buffer[292] & 0xFF) << 24;

        if( mDfuFile.fwLength < 32){
            throw new Exception("Firmware length too short");
        }

        // Get VID, PID and version number
        mDfuFile.VID = (mDfuFile.buffer[Length - 11] & 0xFF) << 8;
        mDfuFile.VID |= (mDfuFile.buffer[Length - 12] & 0xFF);
        mDfuFile.PID = (mDfuFile.buffer[Length - 13] & 0xFF) << 8;
        mDfuFile.PID |= (mDfuFile.buffer[Length - 14] & 0xFF);
        mDfuFile.Version = (mDfuFile.buffer[Length - 15] & 0xFF) << 8;
        mDfuFile.Version |= (mDfuFile.buffer[Length - 16] & 0xFF);
    }

    private int calculateCRC(byte[] FileData) {
        int crc = -1;
        for (int i = 0; i < FileData.length - 4; i++) {
            crc = CrcTable[(crc ^ FileData[i]) & 0xff] ^ (crc >>> 8);
        }
        return crc;
    }

    private void checkCompatibility() throws Exception {

       // if ((mDevicePID != mDfuFile.PID) || (mDeviceVID != mDfuFile.VID)) {
       //     throw new Exception("PID/VID Miss match");
       // }

        // give warning and continue on
        if (mDeviceVersion != mDfuFile.Version) {
            tv.append("Warning: Device Version: " + Integer.toHexString(mDeviceVersion) +
                    "\tFile Version: " + Integer.toHexString(mDfuFile.Version) + "\n");
        }

        switch (mDeviceVersion) {
            case 0x011A:
            case 0x0200:
                mDfuFile.maxBlockSize = 1024;
                break;
            case 0x2100:
            case 0x2200:
                mDfuFile.maxBlockSize = 2048;
                break;
            default:
                throw new Exception("Error: Unsupported bootloader version\n");
        }
    }

    private void writeBlock(int address, byte[] block, int blockNumber) throws Exception {

        DFU_Status dfuStatus = new DFU_Status();

        if (0 == blockNumber) {
            setAddressPointer(address);
            getStatus(dfuStatus);
            getStatus(dfuStatus);
            if (dfuStatus.bState == STATE_DFU_ERROR) {
                throw new Exception("Start address not supported");
            }
        }

        do{
            clearStatus();
            getStatus(dfuStatus);
        }while (dfuStatus.bState != STATE_DFU_IDLE);

        download(block, block.length, (blockNumber + 2));
        getStatus(dfuStatus);   // to execute
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("error when downloading, was not busy ");
        }
        getStatus(dfuStatus);   // to verify action
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("error when downloading, did not perform action");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
    }

    private void detach(int Address) throws Exception {

        DFU_Status dfuStatus = new DFU_Status();
        getStatus(dfuStatus);
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        // Set the command pointer to the new application base address
        setAddressPointer(Address);
        getStatus(dfuStatus);
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        // Issue the DFU detach command
        leaveDfu();
        try {
            getStatus(dfuStatus);
            clearStatus();
            getStatus(dfuStatus);
        } catch (Exception e) {
            // if caught, ignore since device might have disconnected already
        }
    }

    private boolean isDeviceProtected() throws Exception {

        DFU_Status dfuStatus = new DFU_Status();
        boolean isProtected = false;

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(0x08000000);
        getStatus(dfuStatus); // to execute
        getStatus(dfuStatus);   // to verify

        if (dfuStatus.bState == STATE_DFU_ERROR) {
            isProtected = true;
        }

        while (dfuStatus.bState != STATE_DFU_IDLE){
            clearStatus();
            getStatus(dfuStatus);
        }

        return isProtected;
    }

    private void massEraseCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = 0x41;
        download(buffer, 1);
    }

    private void unProtectCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) 0x92;
        download(buffer, 1);
    }

    private void setAddressPointer(int Address) throws Exception {
        byte[] buffer = new byte[5];
        buffer[0] = 0x21;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer, 5);


    }
    private void onePageEraseCommand(int Address) throws Exception {
        //one page = 2048
        byte[] buffer = new byte[5];
        buffer[0] = 0x41;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer, 5);
    }
    private void leaveDfu() throws Exception {
        download(null, 0);
    }

    private void getStatus(DFU_Status status) throws Exception {
        byte[] buffer = new byte[6];
        int length = mUsb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_GETSTATUS, 0, 0, buffer, 6, 500);

        if (length < 0) {
            throw new Exception("USB Failed during getStatus");
        }
        status.bStatus = buffer[0]; // state during request
        status.bState = buffer[4]; // state after request
        status.bwPollTimeout = (buffer[3] & 0xFF) << 16;
        status.bwPollTimeout |= (buffer[2] & 0xFF) << 8;
        status.bwPollTimeout |= (buffer[1] & 0xFF);
    }

    private void clearStatus() throws Exception {
        int length = mUsb.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0);
        if (length < 0) {
            throw new Exception("USB Failed during clearStatus");
        }
    }

    // use for commands
    private void download(byte[] data, int length) throws Exception {
        int len = mUsb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, length, 0);
        if (len < 0) {
            throw new Exception("USB Failed during command download");
        }
    }

    // use for firmware download
    private void download(byte[] data, int length, int nBlock) throws Exception {
        int len = mUsb.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, length, 0);
        if (len < 0) {
            throw new Exception("USB failed during firmware download");
        }
    }

    private void upload(byte[] data, int length, int blockNum) throws Exception {
        int len = mUsb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_UPLOAD, blockNum, 0, data, length, 100);
        if (len < 0) {
            throw new Exception("USB comm failed during upload");
        }
    }

    // stores the result of a GetStatus DFU request
    private class DFU_Status {
        byte bStatus;          // state during request
        int bwPollTimeout;  // minimum time in ms before next getStatus call should be made
        byte bState;// state after request
    }

    // holds all essential information for the Dfu File
    private class DfuFile {
        String filePath;
        byte[] buffer;
        int PID;
        int VID;
        int Version;
        int maxBlockSize = 1024;

        int fwOffset = 293;  // constant offset in buffer where image data starts
        int fwStartAddress;
        int fwLength;
    }

    private static final int[] CrcTable = {
            0x00000000, 0x77073096, 0xee0e612c, 0x990951ba, 0x076dc419, 0x706af48f,
            0xe963a535, 0x9e6495a3, 0x0edb8832, 0x79dcb8a4, 0xe0d5e91e, 0x97d2d988,
            0x09b64c2b, 0x7eb17cbd, 0xe7b82d07, 0x90bf1d91, 0x1db71064, 0x6ab020f2,
            0xf3b97148, 0x84be41de, 0x1adad47d, 0x6ddde4eb, 0xf4d4b551, 0x83d385c7,
            0x136c9856, 0x646ba8c0, 0xfd62f97a, 0x8a65c9ec, 0x14015c4f, 0x63066cd9,
            0xfa0f3d63, 0x8d080df5, 0x3b6e20c8, 0x4c69105e, 0xd56041e4, 0xa2677172,
            0x3c03e4d1, 0x4b04d447, 0xd20d85fd, 0xa50ab56b, 0x35b5a8fa, 0x42b2986c,
            0xdbbbc9d6, 0xacbcf940, 0x32d86ce3, 0x45df5c75, 0xdcd60dcf, 0xabd13d59,
            0x26d930ac, 0x51de003a, 0xc8d75180, 0xbfd06116, 0x21b4f4b5, 0x56b3c423,
            0xcfba9599, 0xb8bda50f, 0x2802b89e, 0x5f058808, 0xc60cd9b2, 0xb10be924,
            0x2f6f7c87, 0x58684c11, 0xc1611dab, 0xb6662d3d, 0x76dc4190, 0x01db7106,
            0x98d220bc, 0xefd5102a, 0x71b18589, 0x06b6b51f, 0x9fbfe4a5, 0xe8b8d433,
            0x7807c9a2, 0x0f00f934, 0x9609a88e, 0xe10e9818, 0x7f6a0dbb, 0x086d3d2d,
            0x91646c97, 0xe6635c01, 0x6b6b51f4, 0x1c6c6162, 0x856530d8, 0xf262004e,
            0x6c0695ed, 0x1b01a57b, 0x8208f4c1, 0xf50fc457, 0x65b0d9c6, 0x12b7e950,
            0x8bbeb8ea, 0xfcb9887c, 0x62dd1ddf, 0x15da2d49, 0x8cd37cf3, 0xfbd44c65,
            0x4db26158, 0x3ab551ce, 0xa3bc0074, 0xd4bb30e2, 0x4adfa541, 0x3dd895d7,
            0xa4d1c46d, 0xd3d6f4fb, 0x4369e96a, 0x346ed9fc, 0xad678846, 0xda60b8d0,
            0x44042d73, 0x33031de5, 0xaa0a4c5f, 0xdd0d7cc9, 0x5005713c, 0x270241aa,
            0xbe0b1010, 0xc90c2086, 0x5768b525, 0x206f85b3, 0xb966d409, 0xce61e49f,
            0x5edef90e, 0x29d9c998, 0xb0d09822, 0xc7d7a8b4, 0x59b33d17, 0x2eb40d81,
            0xb7bd5c3b, 0xc0ba6cad, 0xedb88320, 0x9abfb3b6, 0x03b6e20c, 0x74b1d29a,
            0xead54739, 0x9dd277af, 0x04db2615, 0x73dc1683, 0xe3630b12, 0x94643b84,
            0x0d6d6a3e, 0x7a6a5aa8, 0xe40ecf0b, 0x9309ff9d, 0x0a00ae27, 0x7d079eb1,
            0xf00f9344, 0x8708a3d2, 0x1e01f268, 0x6906c2fe, 0xf762575d, 0x806567cb,
            0x196c3671, 0x6e6b06e7, 0xfed41b76, 0x89d32be0, 0x10da7a5a, 0x67dd4acc,
            0xf9b9df6f, 0x8ebeeff9, 0x17b7be43, 0x60b08ed5, 0xd6d6a3e8, 0xa1d1937e,
            0x38d8c2c4, 0x4fdff252, 0xd1bb67f1, 0xa6bc5767, 0x3fb506dd, 0x48b2364b,
            0xd80d2bda, 0xaf0a1b4c, 0x36034af6, 0x41047a60, 0xdf60efc3, 0xa867df55,
            0x316e8eef, 0x4669be79, 0xcb61b38c, 0xbc66831a, 0x256fd2a0, 0x5268e236,
            0xcc0c7795, 0xbb0b4703, 0x220216b9, 0x5505262f, 0xc5ba3bbe, 0xb2bd0b28,
            0x2bb45a92, 0x5cb36a04, 0xc2d7ffa7, 0xb5d0cf31, 0x2cd99e8b, 0x5bdeae1d,
            0x9b64c2b0, 0xec63f226, 0x756aa39c, 0x026d930a, 0x9c0906a9, 0xeb0e363f,
            0x72076785, 0x05005713, 0x95bf4a82, 0xe2b87a14, 0x7bb12bae, 0x0cb61b38,
            0x92d28e9b, 0xe5d5be0d, 0x7cdcefb7, 0x0bdbdf21, 0x86d3d2d4, 0xf1d4e242,
            0x68ddb3f8, 0x1fda836e, 0x81be16cd, 0xf6b9265b, 0x6fb077e1, 0x18b74777,
            0x88085ae6, 0xff0f6a70, 0x66063bca, 0x11010b5c, 0x8f659eff, 0xf862ae69,
            0x616bffd3, 0x166ccf45, 0xa00ae278, 0xd70dd2ee, 0x4e048354, 0x3903b3c2,
            0xa7672661, 0xd06016f7, 0x4969474d, 0x3e6e77db, 0xaed16a4a, 0xd9d65adc,
            0x40df0b66, 0x37d83bf0, 0xa9bcae53, 0xdebb9ec5, 0x47b2cf7f, 0x30b5ffe9,
            0xbdbdf21c, 0xcabac28a, 0x53b39330, 0x24b4a3a6, 0xbad03605, 0xcdd70693,
            0x54de5729, 0x23d967bf, 0xb3667a2e, 0xc4614ab8, 0x5d681b02, 0x2a6f2b94,
            0xb40bbe37, 0xc30c8ea1, 0x5a05df1b, 0x2d02ef8d
    };
}
