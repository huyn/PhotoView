/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.github.chrisbanes.photoview.sample;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.chrisbanes.photoview.OnDragToFinishListener;
import com.github.chrisbanes.photoview.OnMatrixChangedListener;
import com.github.chrisbanes.photoview.OnPhotoTapListener;
import com.github.chrisbanes.photoview.OnSingleFlingListener;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.Random;


public class TranslateSampleActivity extends AppCompatActivity {

    static final String PHOTO_TAP_TOAST_STRING = "Photo Tap! X: %.2f %% Y:%.2f %% ID: %d";
    static final String SCALE_TOAST_STRING = "Scaled to: %.2ff";
    static final String FLING_LOG_STRING = "Fling velocityX: %.2f, velocityY: %.2f";

    private PhotoView mPhotoView;
    private TextView mCurrMatrixTv;
    private ImageView mImg;
    private Toast mCurrentToast;
    private View mRoot;

    private Matrix mCurrentDisplayMatrix = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_translation_anim);

        mRoot = findViewById(R.id.root);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle("Simple Sample");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_zoom_toggle:
                        mPhotoView.setZoomable(!mPhotoView.isZoomEnabled());
                        item.setTitle(mPhotoView.isZoomEnabled() ? R.string.menu_zoom_disable : R.string.menu_zoom_enable);
                        return true;

                    case R.id.menu_scale_fit_center:
                        mPhotoView.setScaleType(ImageView.ScaleType.CENTER);
                        return true;

                    case R.id.menu_scale_fit_start:
                        mPhotoView.setScaleType(ImageView.ScaleType.FIT_START);
                        return true;

                    case R.id.menu_scale_fit_end:
                        mPhotoView.setScaleType(ImageView.ScaleType.FIT_END);
                        return true;

                    case R.id.menu_scale_fit_xy:
                        mPhotoView.setScaleType(ImageView.ScaleType.FIT_XY);
                        return true;

                    case R.id.menu_scale_scale_center:
                        mPhotoView.setScaleType(ImageView.ScaleType.CENTER);
                        return true;

                    case R.id.menu_scale_scale_center_crop:
                        mPhotoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        return true;

                    case R.id.menu_scale_scale_center_inside:
                        mPhotoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        return true;

                    case R.id.menu_scale_random_animate:
                    case R.id.menu_scale_random:
                        Random r = new Random();

                        float minScale = mPhotoView.getMinimumScale();
                        float maxScale = mPhotoView.getMaximumScale();
                        float randomScale = minScale + (r.nextFloat() * (maxScale - minScale));
                        mPhotoView.setScale(randomScale, item.getItemId() == R.id.menu_scale_random_animate);

                        showToast(String.format(SCALE_TOAST_STRING, randomScale));

                        return true;
                    case R.id.menu_matrix_restore:
                        if (mCurrentDisplayMatrix == null)
                            showToast("You need to capture display matrix first");
                        else
                            mPhotoView.setDisplayMatrix(mCurrentDisplayMatrix);
                        return true;
                    case R.id.menu_matrix_capture:
                        mCurrentDisplayMatrix = new Matrix();
                        mPhotoView.getDisplayMatrix(mCurrentDisplayMatrix);
                        return true;
                }
                return false;
            }
        });
        mPhotoView = (PhotoView) findViewById(R.id.iv_photo);
        mCurrMatrixTv = (TextView) findViewById(R.id.tv_current_matrix);

        mImg = (ImageView) findViewById(R.id.iv_image);
        mImg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPhotoView.setImageBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper));
                popPreview(v);
            }
        });

        findViewById(R.id.iv_image_2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPhotoView.setImageBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.bk_gallery_lightoff));
                popPreview(v);
            }
        });


        // Lets attach some listeners, not required though!
        mPhotoView.setOnMatrixChangeListener(new MatrixChangeListener());
        mPhotoView.setOnPhotoTapListener(new PhotoTapListener());
        mPhotoView.setOnSingleFlingListener(new SingleFlingListener());
        mPhotoView.setDragToFinishListener(600, new OnDragToFinishListener() {
            @Override
            public void onDragged(float fraction) {
                //System.out.println("################" + fraction);
            }

            @Override
            public void onDismiss() {
                //System.out.println("################onDismiss");
            }
        });

        findViewById(R.id.click_to_anim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                int width = mRoot.getWidth();
//                int height = mRoot.getHeight();
//                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImg.getLayoutParams();
//                params.width = width/2;
//                params.height = height/2;
//                mImg.setLayoutParams(params);
//
////                mImg.animate().translationX(width/2).translationY(height/2).setDuration(10000).start();
//
//                mPhotoView.doAnim();

                int w = mPhotoView.getWidth();
                int h = mPhotoView.getHeight();
                mPhotoView.resize(w, h/2);
            }
        });

        findViewById(R.id.click_to_reversa).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int width = mRoot.getWidth();
                int height = mRoot.getHeight();
//                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImg.getLayoutParams();
//                params.width = width/2;
//                params.height = height/2;
//                mImg.setLayoutParams(params);
//
////                mImg.animate().translationX(width/2).translationY(height/2).setDuration(10000).start();
//
//                mPhotoView.doReverse();

                mPhotoView.reverse(width, height, mPhotoView.getWidth(), mPhotoView.getHeight());
            }
        });
    }


    private int mPreviewSizeW = 0;
    private int mPreviewSizeH = 0;
    private Rect mSrcRect;
    private int ANIM_DURATION = 300;
    private int scaleInitCount = 0;
    public void popPreview(View view) {
        final int srcW = view.getWidth();
        final int srcH = view.getHeight();
        scaleInitCount = 0;

        final int mBigW = mRoot.getWidth();//isLong ? (int) (mRoot.getHeight() / sizeRatio) : getWidth();
        final int mBigH = mRoot.getHeight();//isLong ? mRoot.getHeight() : (int) (mBigW * sizeRatio);

        mPreviewSizeW = mBigW;
        mPreviewSizeH = mBigH;

        int[] position = new int[2];
        view.getLocationInWindow(position);
        int y = position[1];
        int x = position[0];

        mRoot.getLocationInWindow(position);
        int statusHeight = position[1];
        y -= statusHeight;

        mSrcRect = new Rect();
        mSrcRect.left = x;
        mSrcRect.top = y;
        mSrcRect.right = x + view.getWidth();
        mSrcRect.bottom = y + view.getHeight();

        mRoot.getLocationOnScreen(position);

        final int dy = (mRoot.getHeight() - mBigH) / 2;
        final int dx = (mRoot.getWidth() - mBigW)/2;

        final float fx = x;
        final float fy = y;

        mPhotoView.setOriginArgs(mRoot.getWidth(), mRoot.getHeight(), srcW, srcH, 0, 0);

//        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPhotoView.getLayoutParams();
//        params.width = srcW;
//        params.height = srcH;
//        mPhotoView.setLayoutParams(params);
//        mPhotoView.setTranslationX(fx);
//        mPhotoView.setTranslationY(fy);
//
//        mPhotoView.resize(0);


        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_DURATION);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                float tx = fx + (dx - fx)*value;
                float ty = fy + (dy - fy)*value;

                int width = (int) (srcW + (mBigW-srcW)*value);
                int height = (int) (srcH + (mBigH-srcH)*value);

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPhotoView.getLayoutParams();
                params.width = width;
                params.height = height;
                mPhotoView.setLayoutParams(params);
                mPhotoView.setTranslationX(tx);
                mPhotoView.setTranslationY(ty);

                if(mPhotoView.resize(animation.getAnimatedFraction())) {
                    if(scaleInitCount> 1)
                        mPhotoView.setAlpha(1f);
                    else
                        scaleInitCount++;
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mPhotoView.setAlpha(0f);
                mPhotoView.setVisibility(View.VISIBLE);
            }
        });
        animator.start();

        //mPaintPreview.setImageBitmap(bitmap);
        //mPaintPreview.setImage(ImageSource.uri(original ? detail.sourceUrl : detail.targetUrl).dimensions(detail.targetWidth, detail.targetHeight), ImageSource.bitmap(bitmap));
        //mPaintPreview.setImage(ImageSource.bitmap(bitmap));
    }

    private boolean dismissPreview() {
        if(mSrcRect == null) {
            return false;
        }

        final int width = mSrcRect.right - mSrcRect.left;
        final int height = mSrcRect.bottom - mSrcRect.top;

        final int mBigW = mRoot.getWidth();//isLong ? (int) (mRoot.getHeight() / sizeRatio) : getWidth();
        final int mBigH = mRoot.getHeight();//isLong ? mRoot.getHeight() : (int) (mBigW * sizeRatio);

        int[] position = new int[2];
        mRoot.getLocationOnScreen(position);

        final int dy = (mRoot.getHeight() - mBigH) / 2;
        final int dx = (mRoot.getWidth() - mBigW)/2;

        final float fx = mSrcRect.left;
        final float fy = mSrcRect.top;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_DURATION);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                float tx = dx + (fx - dx)*value;
                float ty = dy + (fy - dy)*value;

                int targetW = (int) (mBigW + (width-mBigW)*value);
                int targetH = (int) (mBigH + (height-mBigH)*value);

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mPhotoView.getLayoutParams();
                params.width = targetW;
                params.height = targetH;
                mPhotoView.setLayoutParams(params);
                mPhotoView.setTranslationX(tx);
                mPhotoView.setTranslationY(ty);
                mPhotoView.resize(1-animation.getAnimatedFraction());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mPhotoView.setVisibility(View.GONE);
            }
        });
        animator.start();
        return true;
    }

    private class PhotoTapListener implements OnPhotoTapListener {

        @Override
        public void onPhotoTap(ImageView view, float x, float y) {
            float xPercentage = x * 100f;
            float yPercentage = y * 100f;

            showToast(String.format(PHOTO_TAP_TOAST_STRING, xPercentage, yPercentage, view == null ? 0 : view.getId()));

            dismissPreview();
        }
    }

    private void showToast(CharSequence text) {
        if (mCurrentToast != null) {
            mCurrentToast.cancel();
        }

        mCurrentToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        mCurrentToast.show();
    }

    private class MatrixChangeListener implements OnMatrixChangedListener {

        @Override
        public void onMatrixChanged(RectF rect) {
            mCurrMatrixTv.setText(rect.toString());
        }
    }

    private class SingleFlingListener implements OnSingleFlingListener {

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            Log.d("PhotoView", String.format(FLING_LOG_STRING, velocityX, velocityY));
            return true;
        }
    }
}
