package org.deeplearning4j.optimize.listeners.stats.impl;

import lombok.Data;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.deeplearning4j.optimize.listeners.stats.api.StatsInitializationReport;
import org.deeplearning4j.optimize.listeners.stats.sbe.*;

import java.nio.charset.Charset;

/**
 * Created by Alex on 01/10/2016.
 */
@Data
public class SbeStatsInitializationReport implements StatsInitializationReport {

    private static Charset UTF8 = Charset.forName("UTF-8");
    private static byte[] EMPTY_BYTES = new byte[0];    //Also equivalent to "".getBytes(UTF8);

    private boolean hasSoftwareInfo;
    private boolean hasHardwareInfo;
    private boolean hasModelInfo;

    private String swArch;
    private String swOsName;
    private String swJvmName;
    private String swJvmVersion;
    private String swJvmSpecVersion;
    private String swNd4jBackendClass;
    private String swNd4jDataTypeName;

    private int hwJvmAvailableProcessors;
    private int hwNumDevices;
    private long hwJvmMaxMemory;
    private long hwOffHeapMaxMemory;
    private long[] hwDeviceTotalMemory;
    private String[] hwDeviceDescription;

    private String modelClassName;
    private String modelConfigJson;
    private String[] modelParamNames;
    private int modelNumLayers;
    private long modelNumParams;


    @Override
    public void reportSoftwareInfo(String arch, String osName, String jvmName, String jvmVersion, String jvmSpecVersion,
                                   String nd4jBackendClass, String nd4jDataTypeName) {
        this.swArch = arch;
        this.swOsName = osName;
        this.swJvmName = jvmName;
        this.swJvmVersion = jvmVersion;
        this.swJvmSpecVersion = jvmSpecVersion;
        this.swNd4jBackendClass = nd4jBackendClass;
        this.swNd4jDataTypeName = nd4jDataTypeName;
        hasSoftwareInfo = true;
    }

    @Override
    public void reportHardwareInfo(int jvmAvailableProcessors, int numDevices, long jvmMaxMemory, long offHeapMaxMemory,
                                   long[] deviceTotalMemory, String[] deviceDescription) {
        this.hwJvmAvailableProcessors = jvmAvailableProcessors;
        this.hwNumDevices = numDevices;
        this.hwJvmMaxMemory = jvmMaxMemory;
        this.hwOffHeapMaxMemory = offHeapMaxMemory;
        this.hwDeviceTotalMemory = deviceTotalMemory;
        this.hwDeviceDescription = deviceDescription;
        hasHardwareInfo = true;
    }

    @Override
    public void reportModelInfo(String modelClassName, String modelConfigJson, String[] modelParamNames, int numLayers,
                                long numParams) {
        this.modelClassName = modelClassName;
        this.modelConfigJson = modelConfigJson;
        this.modelParamNames = modelParamNames;
        this.modelNumLayers = numLayers;
        this.modelNumParams = numParams;
        hasModelInfo = true;
    }

    @Override
    public byte[] toByteArray() {
        //Recall that the encoding order is VERY important for SBE... must follow the schema exactly

        //TODO we could do this much more efficiently, with buffer re-use, etc.
        MessageHeaderEncoder enc = new MessageHeaderEncoder();
        StaticInfoEncoder sie = new StaticInfoEncoder();


        //First: need to determine how large a buffer to use.
        //Buffer is composed of:
        //(a) Header: 8 bytes (4x uint16 = 8 bytes)
        //(b) Fixed length entries length (sie.BlockLength())
        //(c) Group 1: Hardware devices (GPUs) max memory: 4 bytes header + nEntries * 8 (int64) + nEntries * variable length Strings (header + content)  = 4 + 8*n + content
        //(d) Group 2: Parameter names: 4 bytes header + nEntries * variable length strings (header + content) = 4 + content
        //(e) Variable length fields: 9 String length fields. Size: 4 bytes header, plus content. 36 bytes header
        //Fixed length + repeating groups + variable length...
        int bufferSize = 8 + sie.sbeBlockLength() + 4 + 4 + 36; //header + fixed values + group headers + variable length headers

        //For variable length field lengths: easist way is simply to convert to UTF-8
        //Of course, it is possible to calculate it first - but we might as well convert (1 pass), rather than count then convert (2 passes)
        byte[] bswArch = toBytes(hasSoftwareInfo, swArch);
        byte[] bswOsName = toBytes(hasSoftwareInfo, swOsName);
        byte[] bswJvmName = toBytes(hasSoftwareInfo, swJvmName);
        byte[] bswJvmVersion = toBytes(hasSoftwareInfo, swJvmVersion);
        byte[] bswJvmSpecVersion = toBytes(hasSoftwareInfo, swJvmSpecVersion);
        byte[] bswNd4jBackendClass = toBytes(hasSoftwareInfo, swNd4jBackendClass);
        byte[] bswNd4jDataTypeName = toBytes(hasSoftwareInfo, swNd4jDataTypeName);
        byte[] bmodelConfigClass = toBytes(hasModelInfo, modelClassName);
        byte[] bmodelConfigJson = toBytes(hasModelInfo, modelConfigJson);

        byte[][] bhwDeviceDescription = toBytes(hasHardwareInfo, hwDeviceDescription);
        byte[][] bModelParamNames = toBytes(hasModelInfo, modelParamNames);

        if (hasSoftwareInfo) {
            bufferSize += length(bswArch);
            bufferSize += length(bswOsName);
            bufferSize += length(bswJvmName);
            bufferSize += length(bswJvmVersion);
            bufferSize += length(bswJvmSpecVersion);
            bufferSize += length(bswNd4jBackendClass);
            bufferSize += length(bswNd4jDataTypeName);
        }
        int nHWDeviceStats = (hwDeviceTotalMemory == null ? 0 : hwDeviceTotalMemory.length);
        nHWDeviceStats = Math.max(nHWDeviceStats, (hwDeviceDescription == null ? 0 : hwDeviceDescription.length));
        if(!hasHardwareInfo) nHWDeviceStats = 0;
        if (hasHardwareInfo) {
            //Device info group:
            bufferSize += (hwDeviceTotalMemory == null ? 0 : nHWDeviceStats * 8);   //fixed content in group: int64 -> 8 bytes
            bufferSize += (bhwDeviceDescription == null ? 0 : nHWDeviceStats * 4);     //uint32: 4 bytes per entry for var length header...
//            bufferSize += hwNumDevices * 8;   //fixed content in group: int64 -> 8 bytes
//            bufferSize += hwNumDevices * 4;     //uint32: 4 bytes per entry for var length header...
            bufferSize += length(bhwDeviceDescription);
        }
        if(hasModelInfo){
            bufferSize += length(bmodelConfigClass);
            bufferSize += length(bmodelConfigJson);
            bufferSize += length(bModelParamNames);
            bufferSize += (bModelParamNames == null ? 0 : bModelParamNames.length * 4);   //uint32: 4 bytes per entry for var length header...
        }


        //Now know the
        byte[] bytes = new byte[bufferSize];
        MutableDirectBuffer buffer = new UnsafeBuffer(bytes);

        enc.wrap(buffer, 0)
                .blockLength(sie.sbeBlockLength())
                .templateId(sie.sbeTemplateId())
                .schemaId(sie.sbeSchemaId())
                .version(sie.sbeSchemaVersion());

        int offset = enc.encodedLength();   //Expect 8 bytes...

        //Fixed length fields: always encoded, whether present or not.
        sie.wrap(buffer, offset)
                .time(-1L)  //TODO
                .fieldsPresent()
                .softwareInfo(hasSoftwareInfo)
                .hardwareInfo(hasHardwareInfo)
                .modelInfo(hasModelInfo);
        sie.hwJvmProcessors(hwJvmAvailableProcessors)
                .hwNumDevices((short) hwNumDevices)
                .hwJvmMaxMemory(hwJvmMaxMemory)
                .hwOffheapMaxMemory(hwOffHeapMaxMemory)
                .modelNumLayers(modelNumLayers)
                .modelNumParams(modelNumParams);
        //Device info group...
        StaticInfoEncoder.HwDeviceInfoGroupEncoder hwdEnc = sie.hwDeviceInfoGroupCount(hwNumDevices);
        for(int i=0; i<nHWDeviceStats; i++ ){
            long maxMem = hwDeviceTotalMemory == null || hwDeviceTotalMemory.length <= i ? -1 : hwDeviceTotalMemory[i];
            byte[] descr = bhwDeviceDescription == null || bhwDeviceDescription.length <= i ? EMPTY_BYTES : bhwDeviceDescription[i];
            if(descr == null) descr = EMPTY_BYTES;
            hwdEnc.next().deviceMemoryMax(maxMem).putDeviceDescription(descr,0,descr.length);
        }

        int nParamNames = modelParamNames == null ? 0 : modelParamNames.length;
        StaticInfoEncoder.ModelParamNamesEncoder mpnEnc = sie.modelParamNamesCount(nParamNames);
        for( int i=0; i<nParamNames; i++ ){
            mpnEnc.next().putModelParamNames(bModelParamNames[i],0,bModelParamNames[i].length);
        }

        //In the case of !hasSoftwareInfo: these will all be empty byte arrays... still need to encode them (for 0 length) however
        sie.putSwArch(bswArch, 0, bswArch.length)
                .putSwOsName(bswOsName, 0, bswOsName.length)
                .putSwJvmName(bswJvmName, 0, bswJvmName.length)
                .putSwJvmVersion(bswJvmVersion, 0, bswJvmVersion.length)
                .putSwJvmSpecVersion(bswJvmSpecVersion, 0, bswJvmSpecVersion.length)
                .putSwNd4jBackendClass(bswNd4jBackendClass, 0, bswNd4jBackendClass.length)
                .putSwNd4jDataTypeName(bswNd4jDataTypeName, 0, bswNd4jDataTypeName.length);
        //Similar: !hasModelInfo -> empty byte[]
        sie.putModelConfigClassName(bmodelConfigClass,0,bmodelConfigClass.length)
                .putModelConfigJson(bmodelConfigJson,0,bmodelConfigJson.length);

        offset += sie.encodedLength();
        if(offset != bytes.length){
            throw new RuntimeException();
        }

        return bytes;
    }

    private static int length(byte[] bytes) {
        if (bytes == null) return 0;
        return bytes.length;
    }

    private static int length(byte[][] bytes) {
        if (bytes == null) return 0;
        int count = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != null) count += bytes[i].length;
        }
        return count;
    }

    private static int length(String str) {
        if (str == null) return 0;
        return str.length();
    }

    private static int length(String[] arr) {
        if (arr == null || arr.length == 0) return 0;
        int sum = 0;
        for (String s : arr) sum += length(s);
        return sum;
    }

    private byte[] toBytes(boolean present, String str) {
        if (!present || str == null) return EMPTY_BYTES;
        return str.getBytes(UTF8);
    }

    private byte[][] toBytes(boolean present, String[] str) {
        if (str == null) return null;
        byte[][] b = new byte[str.length][0];
        for (int i = 0; i < str.length; i++) {
            if (str[i] == null) continue;
            b[i] = toBytes(present, str[i]);
        }
        return b;
    }

    @Override
    public void fromByteArray(byte[] bytes) {
        //TODO we could do this much more efficiently, with buffer re-use, etc.
        MessageHeaderDecoder dec = new MessageHeaderDecoder();
        StaticInfoDecoder sid = new StaticInfoDecoder();

        MutableDirectBuffer buffer = new UnsafeBuffer(bytes);
        dec.wrap(buffer,0);

        final int blockLength = dec.blockLength();
        final int version = dec.version();

        int headerLength = dec.encodedLength();
        //TODO: in general, we'd check the header, version, schema etc.

        sid.wrap(buffer,headerLength,blockLength,version);
        long time = sid.time(); //TODO
        InitFieldsPresentDecoder fields = sid.fieldsPresent();
        hasSoftwareInfo = fields.softwareInfo();
        hasHardwareInfo = fields.hardwareInfo();
        hasModelInfo = fields.modelInfo();

        //These fields: always present, even if !hasHardwareInfo
        hwJvmAvailableProcessors = sid.hwJvmProcessors();
        hwNumDevices = sid.hwNumDevices();
        hwJvmMaxMemory = sid.hwJvmMaxMemory();
        hwOffHeapMaxMemory = sid.hwOffheapMaxMemory();
        modelNumLayers = sid.modelNumLayers();
        modelNumParams = sid.modelNumParams();

        StaticInfoDecoder.HwDeviceInfoGroupDecoder hwDeviceInfoGroupDecoder = sid.hwDeviceInfoGroup();
        int count = hwDeviceInfoGroupDecoder.count();
        if(count > 0){
            hwDeviceTotalMemory = new long[count];
            hwDeviceDescription = new String[count];
        }
        int i=0;
        for(StaticInfoDecoder.HwDeviceInfoGroupDecoder hw : hwDeviceInfoGroupDecoder){
            hwDeviceTotalMemory[i] = hw.deviceMemoryMax();
            hwDeviceDescription[i++] = hw.deviceDescription();
        }
        i=0;
        StaticInfoDecoder.ModelParamNamesDecoder mpdec = sid.modelParamNames();
        int mpnCount = mpdec.count();
        modelParamNames = new String[mpnCount];
        for(StaticInfoDecoder.ModelParamNamesDecoder mp : mpdec){
            modelParamNames[i++] = mp.modelParamNames();
        }
        //Variable length data. Even if it is missing: still needs to be read, to advance buffer
        swArch = sid.swArch();
        swOsName = sid.swOsName();
        swJvmName = sid.swJvmName();
        swJvmVersion = sid.swJvmVersion();
        swJvmSpecVersion = sid.swJvmSpecVersion();
        swNd4jBackendClass = sid.swNd4jBackendClass();
        swNd4jDataTypeName = sid.swNd4jDataTypeName();
        if(!hasSoftwareInfo) clearSwFields();
        modelClassName = sid.modelConfigClassName();
        modelConfigJson = sid.modelConfigJson();
        if(!hasModelInfo) clearModelFields();
    }

    @Override
    public boolean hasSoftwareInfo() {
        return hasSoftwareInfo;
    }

    @Override
    public boolean hasHardwareInfo() {
        return hasHardwareInfo;
    }

    @Override
    public boolean hasModelInfo() {
        return hasModelInfo;
    }


    private void clearSwFields(){
        swArch = null;
        swOsName = null;
        swJvmName = null;
        swJvmVersion = null;
        swJvmSpecVersion = null;
        swNd4jBackendClass = null;
        swNd4jDataTypeName = null;
    }

    private void clearModelFields(){
        modelClassName = null;
        modelConfigJson = null;
        modelParamNames = null;
    }
}
