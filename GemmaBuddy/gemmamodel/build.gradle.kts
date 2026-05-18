plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("gemmamodel")
    dynamicDelivery {
        // on-demand: ユーザーが起動したときにPlayからダウンロード
        deliveryType.set("on-demand")
    }
}
