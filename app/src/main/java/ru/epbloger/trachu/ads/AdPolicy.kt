package ru.epbloger.trachu.ads

internal fun adBannerEnabled(adUnitId:String):Boolean = adUnitId.isNotBlank()

internal fun adPersonalization(consent:Boolean?,debug:Boolean):Boolean =
    !debug && consent==true
