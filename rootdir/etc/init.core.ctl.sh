#!/system/bin/sh

################################################################################
# helper functions to allow Android init like script

function write() {
    echo -n $2 > $1
}
################################################################################

# Set "sys.cpu.core_ctl=1" in /system/build.prop
# to load and configure core_ctl module at boot 
if [ $(getprop sys.cpu.core_ctl) != "1" ]; then
    exit 999;
fi

# Load core_ctl module
insmod /system/lib/modules/core_ctl.ko
    
# Configure core_ctl for power cluster
write /sys/devices/system/cpu/cpu0/core_ctl/not_preferred "1 0 1 0"
write /sys/devices/system/cpu/cpu0/core_ctl/min_cpus 2
write /sys/devices/system/cpu/cpu0/core_ctl/max_cpus 4
write /sys/devices/system/cpu/cpu0/core_ctl/busy_up_thres 78
write /sys/devices/system/cpu/cpu0/core_ctl/busy_down_thres 55

# Configure core_ctl for perf cluster
write /sys/devices/system/cpu/cpu4/core_ctl/not_preferred "1 0 0 0"
write /sys/devices/system/cpu/cpu4/core_ctl/min_cpus 1
write /sys/devices/system/cpu/cpu4/core_ctl/max_cpus 4
write /sys/devices/system/cpu/cpu4/core_ctl/busy_up_thres 78
write /sys/devices/system/cpu/cpu4/core_ctl/busy_down_thres 55
write /sys/devices/system/cpu/cpu4/core_ctl/offline_delay_ms 200
write /sys/devices/system/cpu/cpu4/core_ctl/task_thres 4
write /sys/devices/system/cpu/cpu4/core_ctl/is_big_cluster 1
