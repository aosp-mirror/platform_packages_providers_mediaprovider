# For extracting a transcode_compat_manifest from a csv file in the format
# package_name,hevc_support,slow_motion_support,hdr_10_support,hdr_10_plus_support,hdr_hlg_support,hdr_dolby_vision_support,hevc_support_shift,slow_motion_support_shift,hdr_10_support_shift,hd_10_plus_support_shift,hdr_hlg_support_shift,hdr_dolby_vision_support_shift,media_capability
# com.foo,1,0,0,0,0,0,1,0,0,0,0,0,1
# ....
function transcode_compat_manifest() {
    # Cat file
    # Remove CLRF (DOS format)
    # Remove first line (header)
    # Extract first and last columns in each line
    # For device_config convert new lines (\n) to comma(,)
    # For device_config remove trailing comma(,)
    case "$1" in
        -r) cat $2 | tr -d '\r' | sed 1d | awk -F "," '{new_var=$1","$NF; print new_var}';;
        -d) cat $2 | tr -d '\r' | sed 1d | awk -F "," '{new_var=$1","$NF; print new_var}' | tr '\n' ',' | sed 's/,$//g';;
        *) "Enter '-d' for device_config, '-r' for resource";;
    esac
    echo
}
