package com.flashing.tool.protocol

/**
 * Command names for MTK DA V6 (XML-based protocol).
 *
 * UNLIKE the earlier legacy-byte assumption, these command strings and the
 * overall envelope format below were extracted directly from strings present
 * in the user's own DAMT6789.bin (MTK_DA_v6, built 2025-11-25, Transsion
 * vendor build). This is NOT a guess — these are the literal XML tag/command
 * names embedded in that binary. The exact set of supported commands can
 * still differ by DA build, so treat anything not listed here as unverified,
 * but everything below has textual confirmation from the actual DA file.
 *
 * Protocol shape (confirmed from binary strings):
 *
 *   Host -> DA:
 *     <?xml version="1.0" encoding="utf-8"?>
 *     <host><version>1.0</version><command>CMD:SOME-COMMAND</command>
 *       <arg><field>value</field>...</arg>
 *     </host>
 *
 *   For binary transfer (writing a partition), host sends CMD:DOWNLOAD-FILE
 *   with <packet_length> telling the DA how many raw bytes follow immediately
 *   after the XML envelope on the same USB pipe — i.e. XML command first,
 *   then raw binary bytes of that length, repeated per chunk.
 *
 *   For binary transfer out (reading a partition), host sends CMD:UPLOAD-FILE
 *   and the DA responds with raw binary bytes following its own XML response.
 *
 *   DA -> Host (completion):
 *     <?xml version="1.0" encoding="utf-8"?>
 *     <host><version>1.0</version><command>CMD:END</command>
 *       <arg><result>SUCCESSED</result><message>...</message></arg>
 *     </host>
 *
 *   Partition table response (confirmed literal format from binary):
 *     <?xml version="1.0" encoding="utf-8"?><partition_table version="1.0">
 *     <pt><name>%s</name><start>0x%llx</start><size>0x%llx</size></pt>
 *     ...
 *     </partition_table>
 */
object DaCommands {
    // --- Session lifecycle ---
    const val CMD_START = "CMD:START"
    const val CMD_END = "CMD:END"
    const val CMD_REBOOT = "CMD:REBOOT"
    const val CMD_NOTIFY_INIT_HW = "CMD:NOTIFY-INIT-HW"
    const val CMD_SET_HOST_INFO = "CMD:SET-HOST-INFO"
    const val CMD_HOST_SUPPORTED_COMMANDS = "CMD:HOST-SUPPORTED-COMMANDS"
    const val CMD_SET_RUNTIME_PARAMETER = "CMD:SET-RUNTIME-PARAMETER"
    const val CMD_GET_DA_INFO = "CMD:GET-DA-INFO"
    const val CMD_GET_HW_INFO = "CMD:GET-HW-INFO"
    const val CMD_GET_SYS_PROPERTY = "CMD:GET-SYS-PROPERTY"
    const val CMD_PROGRESS_REPORT = "CMD:PROGRESS-REPORT"
    const val CMD_FILE_SYS_OPERATION = "CMD:FILE-SYS-OPERATION"

    // --- Partition operations (the MVP scope: read + write, no SLA bypass) ---
    const val CMD_READ_PARTITION_TABLE = "CMD:READ-PARTITION-TABLE"
    const val CMD_READ_PARTITION = "CMD:READ-PARTITION"
    const val CMD_WRITE_PARTITION = "CMD:WRITE-PARTITION"
    const val CMD_WRITE_PARTITIONS = "CMD:WRITE-PARTITIONS"
    const val CMD_ERASE_PARTITION = "CMD:ERASE-PARTITION"
    const val CMD_READ_FLASH = "CMD:READ-FLASH"
    const val CMD_WRITE_FLASH = "CMD:WRITE-FLASH"
    const val CMD_ERASE_FLASH = "CMD:ERASE-FLASH"
    const val CMD_FLASH_ALL = "CMD:FLASH-ALL"
    const val CMD_FLASH_UPDATE = "CMD:FLASH-UPDATE"
    const val CMD_GET_DOWNLOADED_IMAGE_FEEDBACK = "CMD:GET-DOWNLOADED-IMAGE-FEEDBACK"

    // --- Binary transfer envelopes ---
    const val CMD_DOWNLOAD_FILE = "CMD:DOWNLOAD-FILE"   // host -> device (write)
    const val CMD_UPLOAD_FILE = "CMD:UPLOAD-FILE"       // device -> host (read)

    // --- Security / efuse (present in this DA; NOT wired up by this app — see README) ---
    const val CMD_SECURITY_GET_DEV_FW_INFO = "CMD:SECURITY-GET-DEV-FW-INFO"
    const val CMD_SECURITY_SET_ALLINONE_SIGNATURE = "CMD:SECURITY-SET-ALLINONE-SIGNATURE"
    const val CMD_SECURITY_SET_FLASH_POLICY = "CMD:SECURITY-SET-FLASH-POLICY"
    const val CMD_READ_EFUSE = "CMD:READ-EFUSE"
    const val CMD_WRITE_EFUSE = "CMD:WRITE-EFUSE"
    const val CMD_WRITE_PRIVATE_CERT = "CMD:WRITE-PRIVATE-CERT"

    // --- Result strings seen in the binary ---
    const val RESULT_SUCCESS = "SUCCESSED" // yes, misspelled in the DA itself — match it exactly
    const val RESULT_FAIL_PREFIX = "FAIL"

    const val CHECKSUM_NONE = "CHK_NO"
}
