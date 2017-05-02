/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.github.chrisbanes.photoview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.support.v4.view.MotionEventCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.OverScroller;

/**
 * The component of {@link PhotoView} which does the work allowing for zooming, scaling, panning, etc.
 * It is made public in case you need to subclass something other than {@link ImageView} and still
 * gain the functionality that {@link PhotoView} offers
 */
public class PhotoViewAttacher implements View.OnTouchListener,
        OnGestureListener,
        View.OnLayoutChangeListener {

    private static float DEFAULT_MAX_SCALE = 3.0f;
    private static float DEFAULT_MID_SCALE = 1.75f;
    private static float DEFAULT_MIN_SCALE = 1.0f;
    private static int DEFAULT_ZOOM_DURATION = 200;

    private static final int EDGE_NONE = -1;
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final int EDGE_BOTH = 2;
    private static int SINGLE_TOUCH = 1;

    private Interpolator mInterpolator = new AccelerateDecelerateInterpolator();
    private int mZoomDuration = DEFAULT_ZOOM_DURATION;
    private float mMinScale = DEFAULT_MIN_SCALE;
    private float mMidScale = DEFAULT_MID_SCALE;
    private float mMaxScale = DEFAULT_MAX_SCALE;

    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentIntercept = false;

    private ImageView mImageView;

    // Gesture Detectors
    private GestureDetector mGestureDetector;
    private CustomGestureDetector mScaleDragDetector;

    // These are set so we don't keep allocating them on the heap
    private final Matrix mBaseMatrix = new Matrix();
    private final Matrix mDrawMatrix = new Matrix();
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();
    private final float[] mMatrixValues = new float[9];

    // Listeners
    private OnMatrixChangedListener mMatrixChangeListener;
    private OnPhotoTapListener mPhotoTapListener;
    private OnOutsidePhotoTapListener mOutsidePhotoTapListener;
    private View.OnClickListener mOnClickListener;
    private OnLongClickListener mLongClickListener;
    private OnScaleChangedListener mScaleChangeListener;
    private OnSingleFlingListener mSingleFlingListener;
    private OnDragToFinishListener mDragToFinishListener;

    private FlingRunnable mCurrentFlingRunnable;
    private int mScrollEdge = EDGE_BOTH;
    private float mBaseRotation;

    private boolean mZoomEnabled = true;
    private ScaleType mScaleType = ScaleType.FIT_CENTER;
    private boolean mDragToFinish = false;
    private boolean mEnableDragToFinish = true;
    private boolean mIsDragging = false;

    private float mBaseScale;

    private int mDragToFinishDistance = 500;

    private float mTranslateX=0;
    private float mTranslateY=0;
    private float mAnchorX= 0;
    private float mAnchorY = 0;

    public PhotoViewAttacher(ImageView imageView) {
        mImageView = imageView;
        imageView.setOnTouchListener(this);
        imageView.addOnLayoutChangeListener(this);

        if (imageView.isInEditMode()) {
            return;
        }

        mBaseRotation = 0.0f;

        // Create Gesture Detectors...
        mScaleDragDetector = new CustomGestureDetector(imageView.getContext(), this);

        mGestureDetector = new GestureDetector(imageView.getContext(), new GestureDetector.SimpleOnGestureListener() {

            // forward long click listener
            @Override
            public void onLongPress(MotionEvent e) {
                if (mLongClickListener != null) {
                    mLongClickListener.onLongClick(mImageView);
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float velocityX, float velocityY) {
                if (mSingleFlingListener != null) {
                    if (getScale() > DEFAULT_MIN_SCALE) {
                        return false;
                    }

                    if (MotionEventCompat.getPointerCount(e1) > SINGLE_TOUCH
                            || MotionEventCompat.getPointerCount(e2) > SINGLE_TOUCH) {
                        return false;
                    }

                    return mSingleFlingListener.onFling(e1, e2, velocityX, velocityY);
                }
                return false;
            }
        });

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if(mIsDragging) {
                    return false;
                }
                if (mOnClickListener != null) {
                    mOnClickListener.onClick(mImageView);
                }
                final RectF displayRect = getDisplayRect();

                if (displayRect != null) {
                    final float x = e.getX(), y = e.getY();

                    // Check to see if the user tapped on the photo
                    if (displayRect.contains(x, y)) {

                        float xResult = (x - displayRect.left)
                                / displayRect.width();
                        float yResult = (y - displayRect.top)
                                / displayRect.height();

                        if (mPhotoTapListener != null) {
                            mPhotoTapListener.onPhotoTap(mImageView, xResult, yResult);
                        }
                        return true;
                    } else {
                        if (mOutsidePhotoTapListener != null) {
                            mOutsidePhotoTapListener.onOutsidePhotoTap(mImageView);
                        }
                    }
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent ev) {
                try {
                    float scale = getScale();
                    float x = ev.getX();
                    float y = ev.getY();

                    if (scale < getMediumScale()) {
                        setScale(getMediumScale(), x, y, true);
                    } else if (scale >= getMediumScale() && scale < getMaximumScale()) {
                        setScale(getMaximumScale(), x, y, true);
                    } else {
                        setScale(getMinimumScale(), x, y, true);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Can sometimes happen when getX() and getY() is called
                }

                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                // Wait for the confirmed onDoubleTap() instead
                return false;
            }
        });
    }

    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener newOnDoubleTapListener) {
        this.mGestureDetector.setOnDoubleTapListener(newOnDoubleTapListener);
    }

    public void setOnScaleChangeListener(OnScaleChangedListener onScaleChangeListener) {
        this.mScaleChangeListener = onScaleChangeListener;
    }

    public void setOnSingleFlingListener(OnSingleFlingListener onSingleFlingListener) {
        this.mSingleFlingListener = onSingleFlingListener;
    }

    @Deprecated
    public boolean isZoomEnabled() {
        return mZoomEnabled;
    }

    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    public boolean setDisplayMatrix(Matrix finalMatrix) {
        if (finalMatrix == null) {
            throw new IllegalArgumentException("Matrix cannot be null");
        }

        if (mImageView.getDrawable() == null) {
            return false;
        }

        mSuppMatrix.set(finalMatrix);
        setImageViewMatrix(getDrawMatrix());
        checkMatrixBounds();

        return true;
    }

    public void setBaseRotation(final float degrees) {
        mBaseRotation = degrees % 360;
        update();
        setRotationBy(mBaseRotation);
        checkAndDisplayMatrix();
    }

    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    public float getMinimumScale() {
        return mMinScale;
    }

    public float getMediumScale() {
        return mMidScale;
    }

    public float getMaximumScale() {
        return mMaxScale;
    }

    public float getScale() {
        return (float) Math.sqrt((float) Math.pow(getValue(mSuppMatrix, Matrix.MSCALE_X), 2) + (float) Math.pow(getValue(mSuppMatrix, Matrix.MSKEW_Y), 2));
    }

    public ScaleType getScaleType() {
        return mScaleType;
    }

    private void postTranslateSuppMatrix(float x, float y) {
        mTranslateX+=x;
        mTranslateY+=y;
    }

    private void postScaleAnchor(float anchorX, float anchorY) {
        mAnchorX = anchorX;
        mAnchorY = anchorY;
    }

    @Override
    public void onDrag(boolean isDraggindDown, float dx, float dy) {
        if (mScaleDragDetector.isScaling()) {
            return; // Do not drag if we are already scaling
        }

        mSuppMatrix.postTranslate(dx, dy);
        postTranslateSuppMatrix(dx, dy);
        System.out.println("++++++++++" + dx + "/" + dy);
        if(isDraggindDown) {
            mIsDragging = true;
            computeDrag();
        }
        checkAndDisplayMatrix(isDraggindDown);

        /*
         * Here we decide whether to let the ImageView's parent to start taking
         * over the touch event.
         *
         * First we check whether this function is enabled. We never want the
         * parent to take over if we're scaling. We then check the edge we're
         * on, and the direction of the scroll (i.e. if we're pulling against
         * the edge, aka 'overscrolling', let the parent take over).
         */
        ViewParent parent = mImageView.getParent();
        if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
            if (mScrollEdge == EDGE_BOTH
                    || (mScrollEdge == EDGE_LEFT && dx >= 1f)
                    || (mScrollEdge == EDGE_RIGHT && dx <= -1f)) {
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            }
        } else {
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    @Override
    public void onDragEnd(boolean down) {
        mIsDragging = false;
        reverseFromDrag();
    }

    @Override
    public void onFling(boolean down, float startX, float startY, float velocityX,
                        float velocityY) {
        if(down)
            return;
        mCurrentFlingRunnable = new FlingRunnable(mImageView.getContext());
        mCurrentFlingRunnable.fling(getImageViewWidth(mImageView),
                getImageViewHeight(mImageView), (int) velocityX, (int) velocityY);
        mImageView.post(mCurrentFlingRunnable);
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        // Update our base matrix, as the bounds have changed
//        updateBaseMatrix(mImageView.getDrawable());
    }

    @Override
    public void onScale(float scaleFactor, float focusX, float focusY) {
        if ((getScale() < mMaxScale || scaleFactor < 1f) && (getScale() > mMinScale || scaleFactor > 1f)) {
            if (mScaleChangeListener != null) {
                mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
            }
            mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
            postScaleAnchor(focusX, focusY);
            checkAndDisplayMatrix();
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        boolean handled = false;

        if (mZoomEnabled && Util.hasDrawable((ImageView) v)) {
            mDragToFinish = false;
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    ViewParent parent = v.getParent();
                    // First, disable the Parent from intercepting the touch
                    // event
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }

                    // If we're flinging, and the user presses down, cancel
                    // fling
                    cancelFling();
                    break;

                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    // If the user has zoomed less than min scale, zoom back
                    // to min scale
                    if (getScale() < mMinScale) {
                        RectF rect = getDisplayRect();
                        if (rect != null) {
                            v.post(new AnimatedZoomRunnable(getScale(), mMinScale,
                                    rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    }
                    break;
            }

            // Try the Scale/Drag detector
            if (mScaleDragDetector != null) {
                boolean wasScaling = mScaleDragDetector.isScaling();
                boolean wasDragging = mScaleDragDetector.isDragging();

                handled = mScaleDragDetector.onTouchEvent(ev);

                boolean didntScale = !wasScaling && !mScaleDragDetector.isScaling();
                boolean didntDrag = !wasDragging && !mScaleDragDetector.isDragging();

                mBlockParentIntercept = didntScale && didntDrag;
            }

            // Check to see if the user double tapped
            if (mGestureDetector != null && mGestureDetector.onTouchEvent(ev)) {
                handled = true;
            }

        }

        return handled;
    }

    private void computeDrag() {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final int viewHeight = getImageViewHeight(mImageView);
        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getImageViewWidth(mImageView);
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        } else {
        }
        if(mDragToFinishListener != null && mEnableDragToFinish) {
            if(deltaY < 0) {
                mDragToFinishListener.onDragged(Math.abs(deltaY) / mDragToFinishDistance);
            } else {
                mDragToFinishListener.onDragged(0);
            }
        }
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    public void setMinimumScale(float minimumScale) {
        Util.checkZoomLevels(minimumScale, mMidScale, mMaxScale);
        mMinScale = minimumScale;
    }

    public void setMediumScale(float mediumScale) {
        Util.checkZoomLevels(mMinScale, mediumScale, mMaxScale);
        mMidScale = mediumScale;
    }

    public void setMaximumScale(float maximumScale) {
        Util.checkZoomLevels(mMinScale, mMidScale, maximumScale);
        mMaxScale = maximumScale;
    }

    public void setScaleLevels(float minimumScale, float mediumScale, float maximumScale) {
        Util.checkZoomLevels(minimumScale, mediumScale, maximumScale);
        mMinScale = minimumScale;
        mMidScale = mediumScale;
        mMaxScale = maximumScale;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    public void setOnClickListener(View.OnClickListener listener) {
        mOnClickListener = listener;
    }

    public void setOnMatrixChangeListener(OnMatrixChangedListener listener) {
        mMatrixChangeListener = listener;
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    public void setOnOutsidePhotoTapListener(OnOutsidePhotoTapListener mOutsidePhotoTapListener) {
        this.mOutsidePhotoTapListener = mOutsidePhotoTapListener;
    }

    public void setScale(float scale) {
        setScale(scale, false);
    }

    public void setScale(float scale, boolean animate) {
        setScale(scale,
                (mImageView.getRight()) / 2,
                (mImageView.getBottom()) / 2,
                animate);
    }

    public void setScale(float scale, float focalX, float focalY,
                         boolean animate) {
        // Check to see if the scale is within bounds
        if (scale < mMinScale || scale > mMaxScale) {
            throw new IllegalArgumentException("Scale must be within the range of minScale and maxScale");
        }

        if (animate) {
            mImageView.post(new AnimatedZoomRunnable(getScale(), scale,
                    focalX, focalY));
        } else {
            mSuppMatrix.setScale(scale, scale, focalX, focalY);
            checkAndDisplayMatrix();
        }
    }

    /**
     * Set the zoom interpolator
     *
     * @param interpolator the zoom interpolator
     */
    public void setZoomInterpolator(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    public void setScaleType(ScaleType scaleType) {
        if (Util.isSupportedScaleType(scaleType) && scaleType != mScaleType) {
            mScaleType = scaleType;
            update();
        }
    }

    public boolean isZoomable() {
        return mZoomEnabled;
    }

    public void setZoomable(boolean zoomable) {
        mZoomEnabled = zoomable;
        update();
    }

    public void update() {
        if (mZoomEnabled) {
            // Update the base matrix using the current drawable
            updateBaseMatrix(mImageView.getDrawable());
        } else {
            // Reset the Matrix...
            resetMatrix();
        }
    }

    /**
     * Get the display matrix
     *
     * @param matrix target matrix to copy to
     */
    public void getDisplayMatrix(Matrix matrix) {
        matrix.set(getDrawMatrix());
    }

    /**
     * Get the current support matrix
     */
    public void getSuppMatrix(Matrix matrix) {
        matrix.set(mSuppMatrix);
    }

    private Matrix getDrawMatrix() {
        mDrawMatrix.set(mBaseMatrix);
        mDrawMatrix.postConcat(mSuppMatrix);
        return mDrawMatrix;
    }

    public Matrix getImageMatrix() {
        return mDrawMatrix;
    }

    public void setDragToFinishListener(int distance, OnDragToFinishListener listener) {
        if(distance < 0)
            return;
        this.mDragToFinishDistance = distance;
        this.mDragToFinishListener = listener;
    }

    public void setZoomTransitionDuration(int milliseconds) {
        this.mZoomDuration = milliseconds;
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     Matrix to unpack
     * @param whichValue Which value from Matrix.M* to return
     * @return returned value
     */
    private float getValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays its contents
     */
    private void resetMatrix() {
        mSuppMatrix.reset();
        setRotationBy(mBaseRotation);
        setImageViewMatrix(getDrawMatrix());
        checkMatrixBounds();
    }

    private void setImageViewMatrix(Matrix matrix) {
        mImageView.setImageMatrix(matrix);

        // Call MatrixChangedListener if needed
        if (mMatrixChangeListener != null) {
            RectF displayRect = getDisplayRect(matrix);
            if (displayRect != null) {
                mMatrixChangeListener.onMatrixChanged(displayRect);
            }
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private void checkAndDisplayMatrix() {
//        if (checkMatrixBounds()) {
//            setImageViewMatrix(getDrawMatrix());
//        } else {
//        }
        checkAndDisplayMatrix(false);
    }

    private void checkAndDisplayMatrix(boolean ignoreCheck) {
        if(ignoreCheck || checkMatrixBounds())
            setImageViewMatrix(getDrawMatrix());
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private RectF getDisplayRect(Matrix matrix) {
        Drawable d = mImageView.getDrawable();
        if (d != null) {
            mDisplayRect.set(0, 0, d.getIntrinsicWidth(),
                    d.getIntrinsicHeight());
            matrix.mapRect(mDisplayRect);
            return mDisplayRect;
        }
        return null;
    }

    private void updateBaseMatrix(Drawable drawable) {
        updateBaseMatrix(drawable, false, 0);
    }

    /**
     * Calculate Matrix for FIT_CENTER
     *
     * @param drawable - Drawable being displayed
     */
    float srcH=0,srcW=0;
    float bitmapW, bitmapH;
    private boolean updateBaseMatrix(Drawable drawable, boolean changeScale, float fraction) {
        if (drawable == null) {
            return false;
        }

        if(changeScale && fraction <= 0)
            return false;

        final float viewWidth = mImageView.getWidth();//getImageViewWidth(mImageView);
        final float viewHeight = mImageView.getHeight();//getImageViewHeight(mImageView);
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();

        mBaseMatrix.reset();

        final float widthScale = viewWidth / drawableWidth;
        final float heightScale = viewHeight / drawableHeight;

        RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
        RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);
        float ratioDrawable = bitmapW > 0 ? (bitmapH * 1f / bitmapW) : (drawableHeight * 1f / drawableWidth);
        float ratioCurrent = viewWidth > 0 ? (viewHeight * 1f / viewWidth) : 0;
        float ratioTarget = 1f;

        float targetW=0;
        float targetH=0;
        float scale=1f;
//        if(ratioDrawable > ratioCurrent) {
//            targetW = viewWidth;
//            targetH = viewWidth * ratioDrawable;
//        } else {
//            targetH = viewHeight;
//            targetW = viewHeight / ratioDrawable;
//            scale = viewHeight * 1f / drawableHeight;
//        }

        float targetScale = 0;

        float currentW=0;
        float currentH=0;
        float translateX=0;
        float translateY=0;
        if(mBigWidth > 0 && changeScale) {
            mTempDst = new RectF(0, 0, mBigWidth, mBigHeight);
            ratioTarget = mBigHeight * 1f / mBigWidth;

            if(ratioDrawable < ratioTarget) {
                targetW = mBigWidth;
                targetH = mBigWidth * ratioDrawable;
            } else {
                targetH = mBigHeight;
                targetW = mBigHeight / ratioDrawable;
            }

            if(ratioDrawable > ratioCurrent) {
                currentW = viewWidth;
                currentH = viewWidth * ratioDrawable;
            } else {
                currentH = viewHeight;
                currentW = viewHeight / ratioDrawable;
            }

            float ratioSrc = mSrcHeight * 1f / mSrcWidth;
            //System.out.println("+++++" + srcW + "/" + srcH + "/" + ratioDrawable + "/" + ratioCurrent);
            if(srcH == 0 || srcW == 0) {
                if (ratioDrawable > ratioCurrent) {
                    srcW = mSrcWidth;
                    srcH = mSrcWidth * ratioDrawable;
                } else {
                    srcH = mSrcHeight;
                    srcW = mSrcHeight / ratioDrawable;
                }
                if(ratioCurrent == 0) {
                    srcH = srcW = 0;
                }
            }

            mBaseScale = srcW/targetW;
            //TODO need check scale make sure it will not small than min value
            scale = 1 + (srcW / targetW-1)*(1-fraction);
            //System.out.println(changeScale + "-------" + viewHeight + "/" + viewWidth);
            //System.out.println(ratioDrawable + "/" + ratioCurrent + "###############" + scale + "/" + srcW + "/" + targetW + "/" + fraction);
            if(ratioCurrent > 0) {
                if (ratioDrawable < ratioCurrent)
                    //translateX = -(scale - 1) * targetW / 2 + (viewWidth - mBigWidth) / 2;
                    translateX = (viewWidth - mBigWidth * scale)/2;
                else
                    //translateY = -(scale - 1) * targetH / 2 + (viewHeight - mBigHeight) / 2;
                    translateY = (viewHeight - mBigHeight*scale)/2;
                //translateY = 300;//(scale - 1) * viewHeight / 2;
            }
//            System.out.println("-------------------___" + ratioDrawable + "/" + ratioCurrent);
//            System.out.println("bitmap size:" + bitmapW + "/" + bitmapH + "--" + drawableWidth + "/" + drawableHeight);
//            System.out.println(fraction + "___" + srcW + "/" + srcH + "---" + mSrcWidth + "/" + mSrcHeight);
//            System.out.println(translateX + "/" + translateY + "++++" + (viewHeight - mBigHeight) / 2 + "//" + scale);
//            System.out.println(currentW + "/" + currentH + "--" + viewWidth + "/" + viewHeight + "--" + targetW + "/" + targetH);
            //System.out.println(ratioCurrent + "/" + ratioDrawable + "/" + ratioTarget + "++++++++" + translateX + "/" + translateY + "///" + currentW + "/" + currentH);
        }

            if ((int) mBaseRotation % 180 != 0) {
                if(mBigWidth > 0)
                    mTempSrc = new RectF(0, 0, mBigWidth, mBigHeight);
                else
                    mTempSrc = new RectF(0, 0, drawableHeight, drawableWidth);
            }

        //System.out.println("------------------resize:" + drawableWidth + "/" + drawableHeight + "--" + viewWidth + "/" + viewHeight);
//        mTempDst = new RectF(0, 0, currentW, currentH);
        if(currentH > 0)
            mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
        if(scale > 0 && currentH > 0) {
            mBaseMatrix.postScale(scale, scale);
        }
        if(mBigWidth > 0 && currentW > 0) {
////            System.out.println("~~~~~~~~~~~~~~~" + viewWidth + '/' + mBigWidth);
            mBaseMatrix.postTranslate(translateX, translateY);
        }

        if(currentH > 0) {
            //System.out.println("---------------");
            resetMatrix();
        }

        if(currentH > 0)
            return true;
        return false;
    }

    private boolean checkMatrixBounds() {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return false;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final int viewHeight = getImageViewHeight(mImageView);
        if (height <= viewHeight) {
                deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getImageViewWidth(mImageView);
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        // Finally actually translate the matrix
        mSuppMatrix.postTranslate(deltaX, deltaY);
        postTranslateSuppMatrix(deltaX, deltaY);
        return true;
    }

    private void reverseFromDrag() {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final int viewHeight = getImageViewHeight(mImageView);
        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        final int viewWidth = getImageViewWidth(mImageView);
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        if(deltaY <= -mDragToFinishDistance && mDragToFinishListener != null && mEnableDragToFinish) {
            //doReverse();
            mDragToFinish = true;
            mDragToFinishListener.onDismiss();
            System.out.println("+++++++++change mDragToFinish:" + mDragToFinish);
            return;
        }

        final float targetX = deltaX;
        final float targetY = deltaY;
        mLastX = 0;
        mLastY = 0;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                if(mDragToFinishListener != null && mEnableDragToFinish) {
                    if(targetY < 0) {
                        mDragToFinishListener.onDragged(Math.abs(targetY)*(1-value) / mDragToFinishDistance);
                    } else {
                        mDragToFinishListener.onDragged(0);
                    }
                }

                float tx = targetX * value;
                float ty = targetY * value;

                mSuppMatrix.postTranslate(tx-mLastX, ty-mLastY);
                postTranslateSuppMatrix(tx-mLastX, ty-mLastY);
                mLastX = tx;
                mLastY = ty;
                setImageViewMatrix(getDrawMatrix());
            }
        });
        animator.start();
    }
    private float mLastX, mLastY;

    private int mBigWidth = 0;
    private int mBigHeight = 0;
    private int mSrcWidth=0;
    private int mSrcHeight=0;
    private static final int DURATION = 500;
    public void doAnim() {
        final int width = mImageView.getWidth();
        final int height = mImageView.getHeight();
        if(mBigWidth == 0) {
            mBigWidth = width;

            mSrcWidth = width/2;
        }
        if(mBigHeight == 0) {
            mBigHeight = height;

            mSrcHeight = height/2;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0.5f, 1f);
        animator.setDuration(DURATION);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImageView.getLayoutParams();
                params.width = (int) (width * value);
                params.height = (int) (height*value);
                mImageView.setLayoutParams(params);

                updateBaseMatrix(mImageView.getDrawable(), true, animation.getAnimatedFraction());
            }
        });
        animator.start();
    }

    public void doReverse() {
        if(mBigHeight == 0)
            return;

        //setScale(getMinimumScale(), mImageView.getWidth()/2, mImageView.getHeight()/2, true);

        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0.5f);
        animator.setDuration(DURATION);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImageView.getLayoutParams();
                params.width = (int) (mBigWidth * value);
                params.height = (int) (mBigHeight*value);
                mImageView.setLayoutParams(params);

                updateBaseMatrix(mImageView.getDrawable(), true, 1-animation.getAnimatedFraction());
            }
        });
        animator.start();
    }

    /**
     * resize from w1*h1 to w2*h2
     * recalculate scale, translationX and translationY
     * @param newSizeW
     * @param newSizeH
     */
    private float lastScale;
    public void resize(final int newSizeW, final int newSizeH) {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return;
        }

        if(mImageView.getDrawable() == null)
            return;

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final float viewWidth = mImageView.getWidth();//getImageViewWidth(mImageView);
        final float viewHeight = mImageView.getHeight();//getImageViewHeight(mImageView);
        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        float startScale = mBaseScale;

        final float scale = getScale();

        srcW = srcH = 0;

        final int drawableWidth = mImageView.getDrawable().getIntrinsicWidth();
        final int drawableHeight = mImageView.getDrawable().getIntrinsicHeight();

        RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
        RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);
        float ratioDrawable = bitmapW > 0 ? (bitmapH * 1f / bitmapW) : (drawableHeight * 1f / drawableWidth);
        float ratioCurrent = viewWidth > 0 ? (viewHeight * 1f / viewWidth) : 0;
        float ratioTarget = 1f;

        float targetW=0;
        float targetH=0;

        float currentW=0;
        float currentH=0;
        float translateX=0;
        float translateY=0;

        mTempDst = new RectF(0, 0, newSizeW, newSizeH);
        ratioTarget = newSizeH * 1f / newSizeW;

        if(ratioDrawable < ratioTarget) {
            targetW = newSizeW;
            targetH = newSizeW * ratioDrawable;
        } else {
            targetH = newSizeH;
            targetW = newSizeH / ratioDrawable;
        }

        if(ratioDrawable > ratioCurrent) {
            currentW = viewWidth;
            currentH = viewWidth * ratioDrawable;
        } else {
            currentH = viewHeight;
            currentW = viewHeight / ratioDrawable;
        }

        //System.out.println("+++++" + srcW + "/" + srcH + "/" + ratioDrawable + "/" + ratioCurrent);
        if(srcH == 0 || srcW == 0) {
            if (ratioDrawable < ratioCurrent) {
                srcW = mBigWidth;
                srcH = mBigWidth * ratioDrawable;
            } else {
                srcH = mBigHeight;
                srcW = mBigHeight / ratioDrawable;
            }
            if(ratioCurrent == 0) {
                srcH = srcW = 0;
            }
        }

        float newScale = srcW/targetW;

        final float startX = mTranslateX;//deltaX;
        final float startY = mTranslateY;//deltaY;

        mLastX = 0;
        mLastY = 0;
        lastScale = scale;
        System.out.println("-------:::" + startX + "/" + startY + "/" + scale + "/" + mTranslateX + "/" + mTranslateY);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                float tx = startX * value;
                float ty = startY * value;

                float scaleTmp = scale + (getMinimumScale()-scale)*value;

                int w = (int) (viewWidth + (newSizeW - viewWidth)*value);
                int h = (int) (viewHeight + (newSizeH - viewHeight)*value);

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImageView.getLayoutParams();
                params.width = w;
                params.height = h;
                mImageView.setLayoutParams(params);

                float targetScale = scaleTmp/lastScale;
                System.out.println("+++++++" + scaleTmp + "/" + (tx-mLastX) + "/" + (ty-mLastY) + "/" + targetScale + "//" + lastScale);

                mSuppMatrix.postScale(targetScale, targetScale, mAnchorX, mAnchorY);
//                mSuppMatrix.postScale(targetScale, targetScale);
                lastScale = scaleTmp;
//                mSuppMatrix.postTranslate(tx-mLastX, ty-mLastY); //deprecated if checkMatrixBounds invoked
                mLastY = ty;
                mLastX = tx;

//                setImageViewMatrix(getDrawMatrix());
//                checkMatrixBounds();

                updateMatrix(mImageView.getDrawable(), newSizeW, newSizeH, mBigWidth, mBigHeight, animation.getAnimatedFraction());
                if(getMinimumScale() != scale)
                    checkMatrixBounds();
            }
        });
        animator.start();
    }

    public void reverse(final int newSizeW, final int newSizeH, final int originW, final int originH) {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return;
        }

        if(mImageView.getDrawable() == null)
            return;

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;

        final float viewWidth = mImageView.getWidth();//getImageViewWidth(mImageView);
        final float viewHeight = mImageView.getHeight();//getImageViewHeight(mImageView);
        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
            mScrollEdge = EDGE_BOTH;
        } else if (rect.left > 0) {
            mScrollEdge = EDGE_LEFT;
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
            mScrollEdge = EDGE_RIGHT;
        } else {
            mScrollEdge = EDGE_NONE;
        }

        float startScale = mBaseScale;

        final float scale = getScale();

        srcW = srcH = 0;

        final float startX = mTranslateX;//deltaX;
        final float startY = mTranslateY;//deltaY;

        mLastX = 0;
        mLastY = 0;
        lastScale = scale;
//        System.out.println("-------:::" + startX + "/" + startY + "/" + scale + "/" + mTranslateX + "/" + mTranslateY);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();

                float tx = startX * value;
                float ty = startY * value;

                float scaleTmp = scale + (getMinimumScale()-scale)*value;

                int w = (int) (viewWidth + (newSizeW - viewWidth)*value);
                int h = (int) (viewHeight + (newSizeH - viewHeight)*value);

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mImageView.getLayoutParams();
                params.width = w;
                params.height = h;
                mImageView.setLayoutParams(params);

                float targetScale = scaleTmp/lastScale;
//                System.out.println("+++++++" + scaleTmp + "/" + (tx-mLastX) + "/" + (ty-mLastY) + "/" + targetScale + "//" + lastScale);

                mSuppMatrix.postScale(targetScale, targetScale, mAnchorX, mAnchorY);
//                mSuppMatrix.postScale(targetScale, targetScale);
                lastScale = scaleTmp;
//                mSuppMatrix.postTranslate(tx-mLastX, ty-mLastY); //deprecated if checkMatrixBounds invoked
                mLastY = ty;
                mLastX = tx;

//                setImageViewMatrix(getDrawMatrix());
//                checkMatrixBounds();

                updateMatrix(mImageView.getDrawable(), newSizeW, newSizeH, originW, originH, animation.getAnimatedFraction());
                if(getMinimumScale() != scale)
                    checkMatrixBounds();
            }
        });
        animator.start();
    }

    private void updateMatrix(Drawable drawable, int sizeW, int sizeH, int originW, int originH, float fraction) {
        if (drawable == null) {
            return;
        }

        mBaseMatrix.reset();

        final float viewWidth = mImageView.getWidth();//getImageViewWidth(mImageView);
        final float viewHeight = mImageView.getHeight();//getImageViewHeight(mImageView);
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();

        RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
        RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);
        float ratioDrawable = bitmapW > 0 ? (bitmapH * 1f / bitmapW) : (drawableHeight * 1f / drawableWidth);
        float ratioCurrent = viewWidth > 0 ? (viewHeight * 1f / viewWidth) : 0;
        float ratioTarget = 1f;

        float targetW=0;
        float targetH=0;
        float scale=1f;

        float currentW=0;
        float currentH=0;
        float translateX=0;
        float translateY=0;

        mTempDst = new RectF(0, 0, sizeW, sizeH);
        ratioTarget = sizeH * 1f / sizeW;

        if(ratioDrawable < ratioTarget) {
            targetW = sizeW;
            targetH = sizeW * ratioDrawable;
        } else {
            targetH = sizeH;
            targetW = sizeH / ratioDrawable;
        }

        if(ratioDrawable > ratioCurrent) {
            currentW = viewWidth;
            currentH = viewWidth * ratioDrawable;
        } else {
            currentH = viewHeight;
            currentW = viewHeight / ratioDrawable;
        }

        //System.out.println("+++++" + srcW + "/" + srcH + "/" + ratioDrawable + "/" + ratioCurrent);
        if(srcH == 0 || srcW == 0) {
            if (ratioDrawable < ratioCurrent) {
                srcW = originW;
                srcH = originW * ratioDrawable;
            } else {
                srcH = originH;
                srcW = originH / ratioDrawable;
            }
            if(ratioCurrent == 0) {
                srcH = srcW = 0;
            }
        }

        float newScale = srcW/targetW;
        //newScale = 2;//1/newScale;

        //TODO need check scale make sure it will not small than min value
        scale = newScale + (1 - newScale) * fraction;

        System.out.println("-------" + viewHeight + "/" + viewWidth + "---" + mBaseScale + "/" + newScale);
        System.out.println(ratioDrawable + "/" + ratioCurrent + "###############" + scale + "/" + originW + "/" + srcW + "/" + targetW + "/" + fraction);
        if(ratioCurrent > 0) {
            if (ratioDrawable < ratioCurrent)
                //translateX = -(scale - 1) * targetW / 2 + (viewWidth - sizeW) / 2;
                translateX = (viewWidth - sizeW * scale)/2;
            else
                //translateY = -(scale - 1) * targetH / 2 + (viewHeight - sizeH) / 2;
                translateY = (viewHeight - sizeH*scale)/2;
            //translateY = 300;//(scale - 1) * viewHeight / 2;
            if(srcW != targetW) {
                translateX = -sizeW * (scale - 1) / 2;
                translateY = 0;//200 * (1 - fraction);//sizeH*(scale-1)/2;
            } else {
                translateX = 0;
                translateY = (originH - sizeH)*(1-fraction)/2;
            }
        }
            System.out.println("-------------------___" + ratioDrawable + "/" + ratioCurrent);
            System.out.println("bitmap size:" + bitmapW + "/" + bitmapH + "--" + drawableWidth + "/" + drawableHeight);
            System.out.println(fraction + "___" + srcW + "/" + srcH + "---" + mSrcWidth + "/" + mSrcHeight);
            System.out.println(translateX + "/" + translateY + "++++" + (viewHeight - sizeH) / 2 + "//" + scale);
            System.out.println(currentW + "/" + currentH + "--" + viewWidth + "/" + viewHeight + "--" + targetW + "/" + targetH);
        //System.out.println(ratioCurrent + "/" + ratioDrawable + "/" + ratioTarget + "++++++++" + translateX + "/" + translateY + "///" + currentW + "/" + currentH);

        if ((int) mBaseRotation % 180 != 0) {
            if(sizeW > 0)
                mTempSrc = new RectF(0, 0, sizeW, sizeH);
            else
                mTempSrc = new RectF(0, 0, drawableHeight, drawableWidth);
        }

        //System.out.println("------------------resize:" + drawableWidth + "/" + drawableHeight + "--" + viewWidth + "/" + viewHeight);
//        mTempDst = new RectF(0, 0, currentW, currentH);
        if(currentH > 0)
            mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
        if(scale > 0 && currentH > 0) {
            mBaseMatrix.postScale(scale, scale);
        }
        if(sizeW > 0 && currentW > 0) {
////            System.out.println("~~~~~~~~~~~~~~~" + viewWidth + '/' + sizeW);
            mBaseMatrix.postTranslate(translateX, translateY);
        }

//        setRotationBy(mBaseRotation);
        setImageViewMatrix(getDrawMatrix());
        //checkMatrixBounds();
    }

    public void setOriginArgs(int fullSizeW, int fullSizeH, int srcW, int srcH, int bitmapW, int bitmapH) {
        this.mBigWidth = fullSizeW;
        this.mBigHeight = fullSizeH;
        this.mSrcWidth = srcW;
        this.mSrcHeight = srcH;
        this.srcW=0;
        this.srcH=0;
        this.bitmapW=bitmapW;
        this.bitmapH=bitmapH;
    }

    public boolean resize(float fraction) {
        return updateBaseMatrix(mImageView.getDrawable(), true, fraction);
    }

    public boolean isScaleOrDragged() {
        System.out.println("++++++isScaleOrDragged:" + mDragToFinish + "/" + (getScale() == getMinimumScale()));
        return getScale() > getMinimumScale() || mDragToFinish;
    }

    public boolean isScaled() {
        return getScale() > getMinimumScale();
    }

    public void enableDragToFinish(boolean enable) {
        this.mEnableDragToFinish = enable;
    }

    private int getImageViewWidth(ImageView imageView) {
        return imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight();
    }

    private int getImageViewHeight(ImageView imageView) {
        return imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom();
    }

    private void cancelFling() {
        if (mCurrentFlingRunnable != null) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
        }
    }

    private class AnimatedZoomRunnable implements Runnable {

        private final float mFocalX, mFocalY;
        private final long mStartTime;
        private final float mZoomStart, mZoomEnd;

        public AnimatedZoomRunnable(final float currentZoom, final float targetZoom,
                                    final float focalX, final float focalY) {
            mFocalX = focalX;
            mFocalY = focalY;
            mStartTime = System.currentTimeMillis();
            mZoomStart = currentZoom;
            mZoomEnd = targetZoom;
        }

        @Override
        public void run() {

            float t = interpolate();
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);
            float deltaScale = scale / getScale();

            onScale(deltaScale, mFocalX, mFocalY);

            // We haven't hit our target scale yet, so post ourselves again
            if (t < 1f) {
                Compat.postOnAnimation(mImageView, this);
            }
        }

        private float interpolate() {
            float t = 1f * (System.currentTimeMillis() - mStartTime) / mZoomDuration;
            t = Math.min(1f, t);
            t = mInterpolator.getInterpolation(t);
            return t;
        }
    }

    private class FlingRunnable implements Runnable {

        private final OverScroller mScroller;
        private int mCurrentX, mCurrentY;

        public FlingRunnable(Context context) {
            mScroller = new OverScroller(context);
        }

        public void cancelFling() {
            mScroller.forceFinished(true);
        }

        public void fling(int viewWidth, int viewHeight, int velocityX,
                          int velocityY) {
            final RectF rect = getDisplayRect();
            if (rect == null) {
                return;
            }

            final int startX = Math.round(-rect.left);
            final int minX, maxX, minY, maxY;

            if (viewWidth < rect.width()) {
                minX = 0;
                maxX = Math.round(rect.width() - viewWidth);
            } else {
                minX = maxX = startX;
            }

            final int startY = Math.round(-rect.top);
            if (viewHeight < rect.height()) {
                minY = 0;
                maxY = Math.round(rect.height() - viewHeight);
            } else {
                minY = maxY = startY;
            }

            mCurrentX = startX;
            mCurrentY = startY;

            // If we actually can move, fling the scroller
            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX,
                        maxX, minY, maxY, 0, 0);
            }
        }

        @Override
        public void run() {
            if (mScroller.isFinished()) {
                return; // remaining post that should not be handled
            }

            if (mScroller.computeScrollOffset()) {

                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();

                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                postTranslateSuppMatrix(mCurrentX-newX, mCurrentY-newY);
                setImageViewMatrix(getDrawMatrix());

                mCurrentX = newX;
                mCurrentY = newY;

                // Post On animation
                Compat.postOnAnimation(mImageView, this);
            }
        }
    }
}
