package io.voyager.samsungbarunhospital.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import io.voyager.samsungbarunhospital.R;
import io.voyager.samsungbarunhospital.model.ButtonConfig;

/**
 * {@link ButtonConfig}의 이미지를 ImageView에 그리는 공통 로직(우선순위: imagePath → drawableRes →
 * 기본 placeholder). MainActivity(버튼 실행 화면)와 AdminSettingsActivity(버튼 관리 화면)가 동일한
 * 규칙을 따로 구현하고 있던 것을 하나로 모았다.
 */
final class ButtonImageLoader {

    private ButtonImageLoader() {}

    interface BitmapConsumer {
        void accept(Bitmap bitmap);
    }

    /**
     * imagePath가 있고 디코딩에 성공하면 ImageView에 반영하고 true를 반환한다(디코딩된 비트맵은
     * onBitmapDecoded로 전달 — 호출자가 recycle 시점을 직접 관리해야 하면 여기서 추적한다).
     * imagePath가 없거나 디코딩에 실패하면 아무것도 하지 않고 false를 반환한다 — 호출자가
     * {@link #setDrawableOrPlaceholder}로 폴백해야 한다.
     */
    static boolean tryLoadCustomImage(ImageView iv, ButtonConfig config, BitmapConsumer onBitmapDecoded) {
        if (config.imagePath == null) return false;
        Bitmap bm = BitmapFactory.decodeFile(config.imagePath);
        if (bm == null) return false;
        iv.setImageBitmap(bm);
        if (onBitmapDecoded != null) onBitmapDecoded.accept(bm);
        return true;
    }

    static void setDrawableOrPlaceholder(ImageView iv, Context ctx, String drawableRes) {
        if (drawableRes != null && !drawableRes.isEmpty()) {
            int resId = ctx.getResources().getIdentifier(drawableRes, "drawable", ctx.getPackageName());
            if (resId != 0) {
                iv.setImageResource(resId);
                return;
            }
        }
        iv.setBackgroundResource(R.drawable.ic_button_placeholder);
    }
}
