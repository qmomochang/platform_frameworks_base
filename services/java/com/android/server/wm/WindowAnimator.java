// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

import static com.android.server.wm.WindowManagerService.LayoutFields.SET_UPDATE_ROTATION;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_WALLPAPER_MAY_CHANGE;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_FORCE_HIDING_CHANGED;
import static com.android.server.wm.WindowManagerService.LayoutFields.SET_ORIENTATION_CHANGE_COMPLETE;

import static com.android.server.wm.WindowManagerService.H.UPDATE_ANIM_PARAMETERS;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;

import com.android.server.wm.WindowManagerService.AppWindowAnimParams;
import com.android.server.wm.WindowManagerService.LayoutToAnimatorParams;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = "WindowAnimator";

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;

    boolean mAnimating;

    final Runnable mAnimationRunnable;

    int mAdjResult;

    // Layout changes for individual Displays. Indexed by displayId.
    SparseIntArray mPendingLayoutChanges = new SparseIntArray();

    // TODO: Assign these from each iteration through DisplayContent. Only valid between loops.
    /** Overall window dimensions */
    int mDw, mDh;

    /** Interior window dimensions */
    int mInnerDw, mInnerDh;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    private int mAnimTransactionSequence;

    // Window currently running an animation that has requested it be detached
    // from the wallpaper.  This means we need to ensure the wallpaper is
    // visible behind it in case it animates in a way that would allow it to be
    // seen. If multiple windows satisfy this, use the lowest window.
    WindowState mWindowDetachedWallpaper = null;

    WindowStateAnimator mUniverseBackground = null;
    int mAboveUniverseLayer = 0;

    int mBulkUpdateParams = 0;

    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators =
            new SparseArray<WindowAnimator.DisplayContentsAnimator>();

    static final int WALLPAPER_ACTION_PENDING = 1;
    int mPendingActions;

    WindowState mWallpaperTarget = null;
    AppWindowAnimator mWpAppAnimator = null;
    WindowState mLowerWallpaperTarget = null;
    WindowState mUpperWallpaperTarget = null;

    ArrayList<AppWindowAnimator> mAppAnimators = new ArrayList<AppWindowAnimator>();

    ArrayList<WindowToken> mWallpaperTokens = new ArrayList<WindowToken>();

    /** Parameters being passed from this into mService. */
    static class AnimatorToLayoutParams {
        boolean mUpdateQueued;
        int mBulkUpdateParams;
        SparseIntArray mPendingLayoutChanges;
        WindowState mWindowDetachedWallpaper;
    }
    /** Do not modify unless holding mService.mWindowMap or this and mAnimToLayout in that order */
    final AnimatorToLayoutParams mAnimToLayout = new AnimatorToLayoutParams();

    boolean mInitialized = false;

    // forceHiding states.
    static final int KEYGUARD_NOT_SHOWN     = 0;
    static final int KEYGUARD_ANIMATING_IN  = 1;
    static final int KEYGUARD_SHOWN         = 2;
    static final int KEYGUARD_ANIMATING_OUT = 3;
    int mForceHiding = KEYGUARD_NOT_SHOWN;

    private String forceHidingToString() {
        switch (mForceHiding) {
            case KEYGUARD_NOT_SHOWN:    return "KEYGUARD_NOT_SHOWN";
            case KEYGUARD_ANIMATING_IN: return "KEYGUARD_ANIMATING_IN";
            case KEYGUARD_SHOWN:        return "KEYGUARD_SHOWN";
            case KEYGUARD_ANIMATING_OUT:return "KEYGUARD_ANIMATING_OUT";
            default: return "KEYGUARD STATE UNKNOWN " + mForceHiding;
        }
    }

    WindowAnimator(final WindowManagerService service) {
        mService = service;
        mContext = service.mContext;
        mPolicy = service.mPolicy;

        mAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                // TODO(cmautner): When full isolation is achieved for animation, the first lock
                // goes away and only the WindowAnimator.this remains.
                synchronized(mService.mWindowMap) {
                    synchronized(WindowAnimator.this) {
                        copyLayoutToAnimParamsLocked();
                        animateLocked();
                    }
                }
            }
        };
    }

    void addDisplayLocked(final int displayId) {
        // Create the DisplayContentsAnimator object by retrieving it.
        getDisplayContentsAnimatorLocked(displayId);
        if (displayId == Display.DEFAULT_DISPLAY) {
            mInitialized = true;
        }
    }

    void removeDisplayLocked(final int displayId) {
        final DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator != null) {
            if (displayAnimator.mWindowAnimationBackgroundSurface != null) {
                displayAnimator.mWindowAnimationBackgroundSurface.kill();
                displayAnimator.mWindowAnimationBackgroundSurface = null;
            }
            if (displayAnimator.mScreenRotationAnimation != null) {
                displayAnimator.mScreenRotationAnimation.kill();
                displayAnimator.mScreenRotationAnimation = null;
            }
            if (displayAnimator.mDimAnimator != null) {
                displayAnimator.mDimAnimator.kill();
                displayAnimator.mDimAnimator = null;
            }
        }

        mDisplayContentsAnimators.delete(displayId);
    }

    /** Locked on mAnimToLayout */
    void updateAnimToLayoutLocked() {
        final AnimatorToLayoutParams animToLayout = mAnimToLayout;
        synchronized (animToLayout) {
            animToLayout.mBulkUpdateParams = mBulkUpdateParams;
            animToLayout.mPendingLayoutChanges = mPendingLayoutChanges.clone();
            animToLayout.mWindowDetachedWallpaper = mWindowDetachedWallpaper;

            if (!animToLayout.mUpdateQueued) {
                animToLayout.mUpdateQueued = true;
                mService.mH.sendMessage(mService.mH.obtainMessage(UPDATE_ANIM_PARAMETERS));
            }
        }
    }

    /** Copy all WindowManagerService params into local params here. Locked on 'this'. */
    private void copyLayoutToAnimParamsLocked() {
        final LayoutToAnimatorParams layoutToAnim = mService.mLayoutToAnim;
        synchronized(layoutToAnim) {
            layoutToAnim.mAnimationScheduled = false;

            if (!layoutToAnim.mParamsModified) {
                return;
            }
            layoutToAnim.mParamsModified = false;

            if ((layoutToAnim.mChanges & LayoutToAnimatorParams.WALLPAPER_TOKENS_CHANGED) != 0) {
                layoutToAnim.mChanges &= ~LayoutToAnimatorParams.WALLPAPER_TOKENS_CHANGED;
                mWallpaperTokens = new ArrayList<WindowToken>(layoutToAnim.mWallpaperTokens);
            }

            mWallpaperTarget = layoutToAnim.mWallpaperTarget;
            mWpAppAnimator = mWallpaperTarget == null
                    ? null : mWallpaperTarget.mAppToken == null
                            ? null : mWallpaperTarget.mAppToken.mAppAnimator;
            mLowerWallpaperTarget = layoutToAnim.mLowerWallpaperTarget;
            mUpperWallpaperTarget = layoutToAnim.mUpperWallpaperTarget;

            // Set the new DimAnimator params.
            final int numDisplays = mDisplayContentsAnimators.size();
            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                displayAnimator.mWinAnimators.clear();
                final WinAnimatorList winAnimators = layoutToAnim.mWinAnimatorLists.get(displayId);
                if (winAnimators != null) {
                    displayAnimator.mWinAnimators.addAll(winAnimators);
                }

                DimAnimator.Parameters dimParams = layoutToAnim.mDimParams.get(displayId);
                if (dimParams == null) {
                    displayAnimator.mDimParams = null;
                } else {
                    final WindowStateAnimator newWinAnimator = dimParams.mDimWinAnimator;

                    // Only set dim params on the highest dimmed layer.
                    final WindowStateAnimator existingDimWinAnimator =
                            displayAnimator.mDimParams == null ?
                                    null : displayAnimator.mDimParams.mDimWinAnimator;
                    // Don't turn on for an unshown surface, or for any layer but the highest
                    // dimmed layer.
                    if (newWinAnimator.mSurfaceShown && (existingDimWinAnimator == null
                            || !existingDimWinAnimator.mSurfaceShown
                            || existingDimWinAnimator.mAnimLayer < newWinAnimator.mAnimLayer)) {
                        displayAnimator.mDimParams = new DimAnimator.Parameters(dimParams);
                    }
                }
            }

            mAppAnimators.clear();
            final int N = layoutToAnim.mAppWindowAnimParams.size();
            for (int i = 0; i < N; i++) {
                final AppWindowAnimParams params = layoutToAnim.mAppWindowAnimParams.get(i);
                AppWindowAnimator appAnimator = params.mAppAnimator;
                appAnimator.mAllAppWinAnimators.clear();
                appAnimator.mAllAppWinAnimators.addAll(params.mWinAnimators);
                mAppAnimators.add(appAnimator);
            }
        }
    }

    void hideWallpapersLocked(final WindowState w) {
        if ((mWallpaperTarget == w && mLowerWallpaperTarget == null) || mWallpaperTarget == null) {
            final int numTokens = mWallpaperTokens.size();
            for (int i = numTokens - 1; i >= 0; i--) {
                final WindowToken token = mWallpaperTokens.get(i);
                final int numWindows = token.windows.size();
                for (int j = numWindows - 1; j >= 0; j--) {
                    final WindowState wallpaper = token.windows.get(j);
                    final WindowStateAnimator winAnimator = wallpaper.mWinAnimator;
                    if (!winAnimator.mLastHidden) {
                        winAnimator.hide();
                        mService.dispatchWallpaperVisibility(wallpaper, false);
                        setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                    }
                }
                token.hidden = true;
            }
        }
    }

    private void updateAppWindowsLocked() {
        int i;
        final int NAT = mAppAnimators.size();
        for (i=0; i<NAT; i++) {
            final AppWindowAnimator appAnimator = mAppAnimators.get(i);
            final boolean wasAnimating = appAnimator.animation != null
                    && appAnimator.animation != AppWindowAnimator.sDummyAnimation;
            if (appAnimator.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                setAppLayoutChanges(appAnimator, WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                        "appToken " + appAnimator.mAppToken + " done");
                if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                        "updateWindowsApps...: done animating " + appAnimator.mAppToken);
            }
        }

        final int NEAT = mService.mExitingAppTokens.size();
        for (i=0; i<NEAT; i++) {
            final AppWindowAnimator appAnimator = mService.mExitingAppTokens.get(i).mAppAnimator;
            final boolean wasAnimating = appAnimator.animation != null
                    && appAnimator.animation != AppWindowAnimator.sDummyAnimation;
            if (appAnimator.stepAnimationLocked(mCurrentTime, mInnerDw, mInnerDh)) {
                mAnimating = true;
            } else if (wasAnimating) {
                // stopped animating, do one more pass through the layout
                setAppLayoutChanges(appAnimator, WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                    "exiting appToken " + appAnimator.mAppToken + " done");
                if (WindowManagerService.DEBUG_ANIM) Slog.v(TAG,
                        "updateWindowsApps...: done animating exiting " + appAnimator.mAppToken);
            }
        }
    }

    private void updateWindowsLocked(final int displayId) {
        ++mAnimTransactionSequence;

        final WinAnimatorList winAnimatorList =
                getDisplayContentsAnimatorLocked(displayId).mWinAnimators;
        ArrayList<WindowStateAnimator> unForceHiding = null;
        boolean wallpaperInUnForceHiding = false;
        mForceHiding = KEYGUARD_NOT_SHOWN;

        for (int i = winAnimatorList.size() - 1; i >= 0; i--) {
            WindowStateAnimator winAnimator = winAnimatorList.get(i);
            WindowState win = winAnimator.mWin;
            final int flags = winAnimator.mAttrFlags;

            if (winAnimator.mSurface != null) {
                final boolean wasAnimating = winAnimator.mWasAnimating;
                final boolean nowAnimating = winAnimator.stepAnimationLocked(mCurrentTime);

                if (WindowManagerService.DEBUG_WALLPAPER) {
                    Slog.v(TAG, win + ": wasAnimating=" + wasAnimating +
                            ", nowAnimating=" + nowAnimating);
                }

                if (wasAnimating && !winAnimator.mAnimating && mWallpaperTarget == win) {
                    mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                    setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                            WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                    if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                        mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 2",
                            mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY));
                    }
                }

                if (mPolicy.doesForceHide(win, win.mAttrs)) {
                    if (!wasAnimating && nowAnimating) {
                        if (WindowManagerService.DEBUG_ANIM ||
                                WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                                "Animation started that could impact force hide: " + win);
                        mBulkUpdateParams |= SET_FORCE_HIDING_CHANGED;
                        setPendingLayoutChanges(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 3",
                                mPendingLayoutChanges.get(displayId));
                        }
                        mService.mFocusMayChange = true;
                    }
                    if (win.isReadyForDisplay()) {
                        if (nowAnimating) {
                            if (winAnimator.mAnimationIsEntrance) {
                                mForceHiding = KEYGUARD_ANIMATING_IN;
                            } else {
                                mForceHiding = KEYGUARD_ANIMATING_OUT;
                            }
                        } else {
                            mForceHiding = KEYGUARD_SHOWN;
                        }
                    }
                    if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                            "Force hide " + mForceHiding
                            + " hasSurface=" + win.mHasSurface
                            + " policyVis=" + win.mPolicyVisibility
                            + " destroying=" + win.mDestroying
                            + " attHidden=" + win.mAttachedHidden
                            + " vis=" + win.mViewVisibility
                            + " hidden=" + win.mRootToken.hidden
                            + " anim=" + win.mWinAnimator.mAnimation);
                } else if (mPolicy.canBeForceHidden(win, win.mAttrs)) {
                    final boolean hideWhenLocked =
                            (winAnimator.mAttrFlags & FLAG_SHOW_WHEN_LOCKED) == 0;
                    final boolean changed;
                    if (((mForceHiding == KEYGUARD_ANIMATING_IN)
                                && (!winAnimator.isAnimating() || hideWhenLocked))
                            || ((mForceHiding == KEYGUARD_SHOWN) && hideWhenLocked)) {
                        changed = win.hideLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy hidden: " + win);
                    } else {
                        changed = win.showLw(false, false);
                        if (WindowManagerService.DEBUG_VISIBILITY && changed) Slog.v(TAG,
                                "Now policy shown: " + win);
                        if (changed) {
                            if ((mBulkUpdateParams & SET_FORCE_HIDING_CHANGED) != 0
                                    && win.isVisibleNow() /*w.isReadyForDisplay()*/) {
                                if (unForceHiding == null) {
                                    unForceHiding = new ArrayList<WindowStateAnimator>();
                                }
                                unForceHiding.add(winAnimator);
                                if ((flags & FLAG_SHOW_WALLPAPER) != 0) {
                                    wallpaperInUnForceHiding = true;
                                }
                            }
                            if (mCurrentFocus == null || mCurrentFocus.mLayer < win.mLayer) {
                                // We are showing on to of the current
                                // focus, so re-evaluate focus to make
                                // sure it is correct.
                                mService.mFocusMayChange = true;
                            }
                        }
                    }
                    if (changed && (flags & FLAG_SHOW_WALLPAPER) != 0) {
                        mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
                        setPendingLayoutChanges(Display.DEFAULT_DISPLAY,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 4",
                                mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY));
                        }
                    }
                }
            }

            final AppWindowToken atoken = win.mAppToken;
            if (winAnimator.mDrawState == WindowStateAnimator.READY_TO_SHOW) {
                if (atoken == null || atoken.allDrawn) {
                    if (winAnimator.performShowLocked()) {
                        mPendingLayoutChanges.put(displayId,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM);
                        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                            mService.debugLayoutRepeats("updateWindowsAndWallpaperLocked 5",
                                mPendingLayoutChanges.get(displayId));
                        }
                    }
                }
            }
            final AppWindowAnimator appAnimator = winAnimator.mAppAnimator;
            if (appAnimator != null && appAnimator.thumbnail != null) {
                if (appAnimator.thumbnailTransactionSeq != mAnimTransactionSequence) {
                    appAnimator.thumbnailTransactionSeq = mAnimTransactionSequence;
                    appAnimator.thumbnailLayer = 0;
                }
                if (appAnimator.thumbnailLayer < winAnimator.mAnimLayer) {
                    appAnimator.thumbnailLayer = winAnimator.mAnimLayer;
                }
            }
        } // end forall windows

        // If we have windows that are being show due to them no longer
        // being force-hidden, apply the appropriate animation to them.
        if (unForceHiding != null) {
            for (int i=unForceHiding.size()-1; i>=0; i--) {
                Animation a = mPolicy.createForceHideEnterAnimation(wallpaperInUnForceHiding);
                if (a != null) {
                    final WindowStateAnimator winAnimator = unForceHiding.get(i);
                    winAnimator.setAnimation(a);
                    winAnimator.mAnimationIsEntrance = true;
                }
            }
        }
    }

    private void updateWallpaperLocked(int displayId) {
        final DisplayContentsAnimator displayAnimator =
                getDisplayContentsAnimatorLocked(displayId);
        final WinAnimatorList winAnimatorList = displayAnimator.mWinAnimators;
        WindowStateAnimator windowAnimationBackground = null;
        int windowAnimationBackgroundColor = 0;
        WindowState detachedWallpaper = null;
        final DimSurface windowAnimationBackgroundSurface =
                displayAnimator.mWindowAnimationBackgroundSurface;

        for (int i = winAnimatorList.size() - 1; i >= 0; i--) {
            WindowStateAnimator winAnimator = winAnimatorList.get(i);
            if (winAnimator.mSurface == null) {
                continue;
            }

            final int flags = winAnimator.mAttrFlags;
            final WindowState win = winAnimator.mWin;

            // If this window is animating, make a note that we have
            // an animating window and take care of a request to run
            // a detached wallpaper animation.
            if (winAnimator.mAnimating) {
                if (winAnimator.mAnimation != null) {
                    if ((flags & FLAG_SHOW_WALLPAPER) != 0
                            && winAnimator.mAnimation.getDetachWallpaper()) {
                        detachedWallpaper = win;
                    }
                    final int backgroundColor = winAnimator.mAnimation.getBackgroundColor();
                    if (backgroundColor != 0) {
                        if (windowAnimationBackground == null || (winAnimator.mAnimLayer <
                                windowAnimationBackground.mAnimLayer)) {
                            windowAnimationBackground = winAnimator;
                            windowAnimationBackgroundColor = backgroundColor;
                        }
                    }
                }
                mAnimating = true;
            }

            // If this window's app token is running a detached wallpaper
            // animation, make a note so we can ensure the wallpaper is
            // displayed behind it.
            final AppWindowAnimator appAnimator = winAnimator.mAppAnimator;
            if (appAnimator != null && appAnimator.animation != null
                    && appAnimator.animating) {
                if ((flags & FLAG_SHOW_WALLPAPER) != 0
                        && appAnimator.animation.getDetachWallpaper()) {
                    detachedWallpaper = win;
                }

                final int backgroundColor = appAnimator.animation.getBackgroundColor();
                if (backgroundColor != 0) {
                    if (windowAnimationBackground == null || (winAnimator.mAnimLayer <
                            windowAnimationBackground.mAnimLayer)) {
                        windowAnimationBackground = winAnimator;
                        windowAnimationBackgroundColor = backgroundColor;
                    }
                }
            }
        } // end forall windows

        if (mWindowDetachedWallpaper != detachedWallpaper) {
            if (WindowManagerService.DEBUG_WALLPAPER) Slog.v(TAG,
                    "Detached wallpaper changed from " + mWindowDetachedWallpaper
                    + " to " + detachedWallpaper);
            mWindowDetachedWallpaper = detachedWallpaper;
            mBulkUpdateParams |= SET_WALLPAPER_MAY_CHANGE;
        }

        if (windowAnimationBackgroundColor != 0) {
            // If the window that wants black is the current wallpaper
            // target, then the black goes *below* the wallpaper so we
            // don't cause the wallpaper to suddenly disappear.
            int animLayer = windowAnimationBackground.mAnimLayer;
            WindowState win = windowAnimationBackground.mWin;
            if (mWallpaperTarget == win
                    || mLowerWallpaperTarget == win || mUpperWallpaperTarget == win) {
                final int N = winAnimatorList.size();
                for (int i = 0; i < N; i++) {
                    WindowStateAnimator winAnimator = winAnimatorList.get(i);
                    if (winAnimator.mIsWallpaper) {
                        animLayer = winAnimator.mAnimLayer;
                        break;
                    }
                }
            }

            if (windowAnimationBackgroundSurface != null) {
                windowAnimationBackgroundSurface.show(mDw, mDh,
                        animLayer - WindowManagerService.LAYER_OFFSET_DIM,
                        windowAnimationBackgroundColor);
            }
        } else {
            if (windowAnimationBackgroundSurface != null) {
                windowAnimationBackgroundSurface.hide();
            }
        }
    }

    private void testTokenMayBeDrawnLocked() {
        // See if any windows have been drawn, so they (and others
        // associated with them) can now be shown.
        final int NT = mAppAnimators.size();
        for (int i=0; i<NT; i++) {
            AppWindowAnimator appAnimator = mAppAnimators.get(i);
            AppWindowToken wtoken = appAnimator.mAppToken;
            final boolean allDrawn = wtoken.allDrawn;
            if (allDrawn != appAnimator.allDrawn) {
                appAnimator.allDrawn = allDrawn;
                if (allDrawn) {
                    // The token has now changed state to having all
                    // windows shown...  what to do, what to do?
                    if (appAnimator.freezingScreen) {
                        appAnimator.showAllWindowsLocked();
                        mService.unsetAppFreezingScreenLocked(wtoken, false, true);
                        if (WindowManagerService.DEBUG_ORIENTATION) Slog.i(TAG,
                                "Setting mOrientationChangeComplete=true because wtoken "
                                + wtoken + " numInteresting=" + wtoken.numInterestingWindows
                                + " numDrawn=" + wtoken.numDrawnWindows);
                        // This will set mOrientationChangeComplete and cause a pass through layout.
                        setAppLayoutChanges(appAnimator,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER,
                                "testTokenMayBeDrawnLocked: freezingScreen");
                    } else {
                        setAppLayoutChanges(appAnimator,
                                WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM,
                                "testTokenMayBeDrawnLocked");
 
                        // We can now show all of the drawn windows!
                        if (!mService.mOpeningApps.contains(wtoken)) {
                            mAnimating |= appAnimator.showAllWindowsLocked();
                        }
                    }
                }
            }
        }
    }

    private void performAnimationsLocked(final int displayId) {
        updateWindowsLocked(displayId);
        updateWallpaperLocked(displayId);
    }

    // TODO(cmautner): Change the following comment when no longer locked on mWindowMap */
    /** Locked on mService.mWindowMap and this. */
    private void animateLocked() {
        if (!mInitialized) {
            return;
        }

        mPendingLayoutChanges.clear();
        mCurrentTime = SystemClock.uptimeMillis();
        mBulkUpdateParams = SET_ORIENTATION_CHANGE_COMPLETE;
        boolean wasAnimating = mAnimating;
        mAnimating = false;
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
        }

        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                TAG, ">>> OPEN TRANSACTION animateLocked");
        Surface.openTransaction();
        try {
            updateAppWindowsLocked();

            final int numDisplays = mDisplayContentsAnimators.size();
            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                final ScreenRotationAnimation screenRotationAnimation =
                        displayAnimator.mScreenRotationAnimation;
                if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                    if (screenRotationAnimation.stepAnimationLocked(mCurrentTime)) {
                        mAnimating = true;
                    } else {
                        mBulkUpdateParams |= SET_UPDATE_ROTATION;
                        screenRotationAnimation.kill();
                        displayAnimator.mScreenRotationAnimation = null;
                    }
                }

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                performAnimationsLocked(displayId);

                final WinAnimatorList winAnimatorList = displayAnimator.mWinAnimators;
                final int N = winAnimatorList.size();
                for (int j = 0; j < N; j++) {
                    winAnimatorList.get(j).prepareSurfaceLocked(true);
                }
            }

            testTokenMayBeDrawnLocked();

            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                final ScreenRotationAnimation screenRotationAnimation =
                        displayAnimator.mScreenRotationAnimation;
                if (screenRotationAnimation != null) {
                    screenRotationAnimation.updateSurfacesInTransaction();
                }

                final DimAnimator.Parameters dimParams = displayAnimator.mDimParams;
                final DimAnimator dimAnimator = displayAnimator.mDimAnimator;
                if (dimAnimator != null && dimParams != null) {
                    dimAnimator.updateParameters(mContext.getResources(), dimParams, mCurrentTime);
                }
                if (dimAnimator != null && dimAnimator.mDimShown) {
                    mAnimating |= dimAnimator.updateSurface(isDimmingLocked(displayId),
                            mCurrentTime, !mService.okToDisplay());
                }
            }

            if (mService.mWatermark != null) {
                mService.mWatermark.drawIfNeeded();
            }
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            Surface.closeTransaction();
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(
                    TAG, "<<< CLOSE TRANSACTION animateLocked");
        }

        for (int i = mPendingLayoutChanges.size() - 1; i >= 0; i--) {
            if ((mPendingLayoutChanges.valueAt(i)
                    & WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER) != 0) {
                mPendingActions |= WALLPAPER_ACTION_PENDING;
            }
        }

        if (mBulkUpdateParams != 0 || mPendingLayoutChanges.size() > 0) {
            updateAnimToLayoutLocked();
        }

        if (mAnimating) {
            synchronized (mService.mLayoutToAnim) {
                mService.scheduleAnimationLocked();
            }
        } else if (wasAnimating) {
            mService.requestTraversalLocked();
        }
        if (WindowManagerService.DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: exit mAnimating=" + mAnimating
                + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                + " mPendingLayoutChanges(DEFAULT_DISPLAY)="
                + Integer.toHexString(mPendingLayoutChanges.get(Display.DEFAULT_DISPLAY)));
        }
    }

    WindowState mCurrentFocus;
    void setCurrentFocus(final WindowState currentFocus) {
        mCurrentFocus = currentFocus;
    }

    void setDisplayDimensions(final int curWidth, final int curHeight,
                        final int appWidth, final int appHeight) {
        mDw = curWidth;
        mDh = curHeight;
        mInnerDw = appWidth;
        mInnerDh = appHeight;
    }

    boolean isDimmingLocked(int displayId) {
        return getDisplayContentsAnimatorLocked(displayId).mDimParams != null;
    }

    boolean isDimmingLocked(final WindowStateAnimator winAnimator) {
        DimAnimator.Parameters dimParams =
                getDisplayContentsAnimatorLocked(winAnimator.mWin.getDisplayId()).mDimParams;
        return dimParams != null && dimParams.mDimWinAnimator == winAnimator;
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        if (dumpAll) {
            if (mWindowDetachedWallpaper != null) {
                pw.print(prefix); pw.print("mWindowDetachedWallpaper=");
                    pw.println(mWindowDetachedWallpaper);
            }
            pw.print(prefix); pw.print("mAnimTransactionSequence=");
                pw.print(mAnimTransactionSequence);
                pw.println(" mForceHiding=" + forceHidingToString());
            for (int i = 0; i < mDisplayContentsAnimators.size(); i++) {
                pw.print(prefix); pw.print("DisplayContentsAnimator #");
                    pw.println(mDisplayContentsAnimators.keyAt(i));
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);
                final String subPrefix = "  " + prefix;
                final String subSubPrefix = "  " + subPrefix;
                if (displayAnimator.mWindowAnimationBackgroundSurface != null) {
                    pw.println(subPrefix + "mWindowAnimationBackgroundSurface:");
                    displayAnimator.mWindowAnimationBackgroundSurface.printTo(subSubPrefix, pw);
                }
                if (displayAnimator.mDimAnimator != null) {
                    pw.println(subPrefix + "mDimAnimator:");
                    displayAnimator.mDimAnimator.printTo(subSubPrefix, pw);
                } else {
                    pw.println(subPrefix + "no DimAnimator ");
                }
                if (displayAnimator.mDimParams != null) {
                    pw.println(subPrefix + "mDimParams:");
                    displayAnimator.mDimParams.printTo(subSubPrefix, pw);
                } else {
                    pw.println(subPrefix + "no DimParams ");
                }
                if (displayAnimator.mScreenRotationAnimation != null) {
                    pw.println(subPrefix + "mScreenRotationAnimation:");
                    displayAnimator.mScreenRotationAnimation.printTo(subSubPrefix, pw);
                } else {
                    pw.print(subPrefix + "no ScreenRotationAnimation ");
                }
            }
            pw.println();
        }
    }

    void clearPendingActions() {
        synchronized (this) {
            mPendingActions = 0;
        }
    }

    void setPendingLayoutChanges(final int displayId, final int changes) {
        mPendingLayoutChanges.put(displayId, mPendingLayoutChanges.get(displayId) | changes);
    }

    void setAppLayoutChanges(final AppWindowAnimator appAnimator, final int changes, String s) {
        // Used to track which displays layout changes have been done.
        SparseIntArray displays = new SparseIntArray();
        for (int i = appAnimator.mAllAppWinAnimators.size() - 1; i >= 0; i--) {
            WindowStateAnimator winAnimator = appAnimator.mAllAppWinAnimators.get(i);
            final int displayId = winAnimator.mWin.mDisplayContent.getDisplayId();
            if (displays.indexOfKey(displayId) < 0) {
                setPendingLayoutChanges(displayId, changes);
                if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
                    mService.debugLayoutRepeats(s, mPendingLayoutChanges.get(displayId));
                }
                // Keep from processing this display again.
                displays.put(displayId, changes);
            }
        }
    }

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator == null) {
            displayAnimator = new DisplayContentsAnimator(displayId);
            mDisplayContentsAnimators.put(displayId, displayAnimator);
        }
        return displayAnimator;
    }

    void setScreenRotationAnimationLocked(int displayId, ScreenRotationAnimation animation) {
        getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation = animation;
    }

    ScreenRotationAnimation getScreenRotationAnimationLocked(int displayId) {
        return getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation;
    }

    private class DisplayContentsAnimator {
        WinAnimatorList mWinAnimators = new WinAnimatorList();
        DimAnimator mDimAnimator = null;
        DimAnimator.Parameters mDimParams = null;
        DimSurface mWindowAnimationBackgroundSurface = null;
        ScreenRotationAnimation mScreenRotationAnimation = null;

        public DisplayContentsAnimator(int displayId) {
            mDimAnimator = new DimAnimator(mService.mFxSession, displayId);
            mWindowAnimationBackgroundSurface =
                    new DimSurface(mService.mFxSession, displayId);
        }
    }
}
