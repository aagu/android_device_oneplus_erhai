#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

TARGET_IS_TABLET := true

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxxhdpi

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(LOCAL_PATH)/configs/audio/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml

# Boot animation
TARGET_SCREEN_HEIGHT := 3392
TARGET_SCREEN_WIDTH := 2400

# Carrier
PRODUCT_VENDOR_PROPERTIES += \
    ro.carrier=wifi-only

# GMS
WITH_GMS_COMMS_SUITE := false

# Hardware
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml

# Init
PRODUCT_PACKAGES += \
    init.erhai.rc

# Input
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/idc/Vendor_22d9_Product_3869.idc:$(TARGET_COPY_OUT_ODM)/usr/idc/Vendor_22d9_Product_3869.idc

# Keylayout
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/keylayout/Vendor_22d9_Product_3869.kl:$(TARGET_COPY_OUT_ODM)/usr/keylayout/Vendor_22d9_Product_3869.kl

# LiveDisplay
$(call soong_config_set_bool,OPLUS_LINEAGE_LIVEDISPLAY_HAL,ENABLE_AB,true)

# Overlays
PRODUCT_PACKAGES += \
    OPlusFrameworksResTarget \
    OPlusPenResTarget \
    OPlusSettingsProviderResTarget_CN \
    OPlusSettingsProviderResTarget_ROW \
    OPlusSettingsResTarget \
    OPlusSystemUIResTarget

# Power
$(call soong_config_set,qtipower,tap_to_wake_node,/proc/touchpanel/double_tap_enable)

# Regional properties
REGIONAL_PROP_FILES := $(wildcard $(LOCAL_PATH)/properties/*/*.prop)

PRODUCT_COPY_FILES += $(foreach f,$(REGIONAL_PROP_FILES), \
    $(f):$(TARGET_COPY_OUT_ODM)/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)) \
    $(f):$(TARGET_COPY_OUT_RECOVERY)/root/vendor/odm/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)))

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# SSG
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/ssg/sec_channel.rc:$(TARGET_COPY_OUT_VENDOR)/etc/sec_channel.rc

# Wireless pen
PRODUCT_PACKAGES += \
    OplusPen

# Inherit from the common OEM chipset makefile.
$(call inherit-product, device/oneplus/sm8750-common/common.mk)

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/oneplus/erhai/erhai-vendor.mk)

# Avium
include device/oneplus/erhai/avium-common.mk

# Sign build
include vendor/lineage-priv/keys/keys.mk
