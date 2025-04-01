/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Spannable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.common.logging.FLog;
import com.facebook.react.R;
import com.facebook.react.bridge.ReactSoftExceptionLogger;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableNativeMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.common.mapbuffer.MapBuffer;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.IViewManagerWithChildren;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.ReactAccessibilityDelegate;
import com.facebook.react.uimanager.ReactStylesDiffMap;
import com.facebook.react.uimanager.Spacing;
import com.facebook.react.uimanager.StateWrapper;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewDefaults;
import com.facebook.react.uimanager.ViewProps;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.annotations.ReactPropGroup;
import com.facebook.react.views.imagehelper.ResourceDrawableIdHelper;
import com.facebook.react.views.textinput.ReactEditText;
import com.facebook.react.views.textinput.ReactTextInputManager;
import com.facebook.yoga.YogaConstants;
import com.facebook.yoga.YogaMeasureMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Concrete class for {@link ReactTextAnchorViewManager} which represents view managers of anchor
 * {@code <Text>} nodes.
 */
@ReactModule(name = ReactTextViewManager.REACT_CLASS)
public class ReactTextViewManager
    extends ReactTextAnchorViewManager<ReactTextView, ReactTextShadowNode>
    implements IViewManagerWithChildren {

  public static final String TAG = ReactTextViewManager.class.getSimpleName();

  private static final short TX_STATE_KEY_ATTRIBUTED_STRING = 0;
  private static final short TX_STATE_KEY_PARAGRAPH_ATTRIBUTES = 1;
  // used for text input
  private static final short TX_STATE_KEY_HASH = 2;
  private static final short TX_STATE_KEY_MOST_RECENT_EVENT_COUNT = 3;

  private static final int[] SPACING_TYPES = {
          Spacing.ALL, Spacing.LEFT, Spacing.RIGHT, Spacing.TOP, Spacing.BOTTOM,
  };

  @VisibleForTesting public static final String REACT_CLASS = "RCTText";

  protected @Nullable ReactTextViewManagerCallback mReactTextViewManagerCallback;

  public ReactTextViewManager() {
    this(null);
  }

  public ReactTextViewManager(@Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {
    mReactTextViewManagerCallback = reactTextViewManagerCallback;
    setupViewRecycling();
  }

  @Override
  protected ReactTextView prepareToRecycleView(
      @NonNull ThemedReactContext reactContext, ReactTextView view) {
    // BaseViewManager
    super.prepareToRecycleView(reactContext, view);

    // Resets background and borders
    view.recycleView();

    // Defaults from ReactTextAnchorViewManager
    setSelectionColor(view, null);

    return view;
  }

  @Override
  public String getName() {
    return REACT_CLASS;
  }

  @Override
  public ReactTextView createViewInstance(ThemedReactContext context) {
    return new ReactTextView(context);
  }

  @Override
  public void updateExtraData(ReactTextView view, Object extraData) {
    ReactTextUpdate update = (ReactTextUpdate) extraData;
    Spannable spannable = update.getText();
    if (update.containsImages()) {
      TextInlineImageSpan.possiblyUpdateInlineImageSpans(spannable, view);
    }
    view.setText(update);

    // If this text view contains any clickable spans, set a view tag and reset the accessibility
    // delegate so that these can be picked up by the accessibility system.
    ReactClickableSpan[] clickableSpans =
        spannable.getSpans(0, update.getText().length(), ReactClickableSpan.class);

    if (clickableSpans.length > 0) {
      view.setTag(
          R.id.accessibility_links,
          new ReactAccessibilityDelegate.AccessibilityLinks(clickableSpans, spannable));
      ReactAccessibilityDelegate.resetDelegate(
          view, view.isFocusable(), view.getImportantForAccessibility());
    }
  }

  @Override
  public ReactTextShadowNode createShadowNodeInstance() {
    return new ReactTextShadowNode(mReactTextViewManagerCallback);
  }

  public ReactTextShadowNode createShadowNodeInstance(
      @Nullable ReactTextViewManagerCallback reactTextViewManagerCallback) {
    return new ReactTextShadowNode(reactTextViewManagerCallback);
  }

  @Override
  public Class<ReactTextShadowNode> getShadowNodeClass() {
    return ReactTextShadowNode.class;
  }

  @Override
  protected void onAfterUpdateTransaction(ReactTextView view) {
    super.onAfterUpdateTransaction(view);
    view.maybeUpdateTypeface();
    view.updateView();
  }

  public boolean needsCustomLayoutForChildren() {
    return true;
  }

  @Override
  public Object updateState(
      ReactTextView view, ReactStylesDiffMap props, StateWrapper stateWrapper) {
    MapBuffer stateMapBuffer = stateWrapper.getStateDataMapBuffer();
    if (stateMapBuffer != null) {
      return getReactTextUpdate(view, props, stateMapBuffer);
    }
    ReadableNativeMap state = stateWrapper.getStateData();
    if (state == null) {
      return null;
    }

    ReadableMap attributedString = state.getMap("attributedString");
    ReadableMap paragraphAttributes = state.getMap("paragraphAttributes");
    Spannable spanned =
        TextLayoutManager.getOrCreateSpannableForText(
            view.getContext(), attributedString, mReactTextViewManagerCallback);
    view.setSpanned(spanned);

    int textBreakStrategy =
        TextAttributeProps.getTextBreakStrategy(
            paragraphAttributes.getString(ViewProps.TEXT_BREAK_STRATEGY));
    int currentJustificationMode =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? 0 : view.getJustificationMode();

    return new ReactTextUpdate(
        spanned,
        state.hasKey("mostRecentEventCount") ? state.getInt("mostRecentEventCount") : -1,
        false, // TODO add this into local Data
        TextAttributeProps.getTextAlignment(
            props, TextLayoutManager.isRTL(attributedString), view.getGravityHorizontal()),
        textBreakStrategy,
        TextAttributeProps.getJustificationMode(props, currentJustificationMode));
  }

  private Object getReactTextUpdate(ReactTextView view, ReactStylesDiffMap props, MapBuffer state) {

    MapBuffer attributedString = state.getMapBuffer(TX_STATE_KEY_ATTRIBUTED_STRING);
    MapBuffer paragraphAttributes = state.getMapBuffer(TX_STATE_KEY_PARAGRAPH_ATTRIBUTES);
    Spannable spanned =
        TextLayoutManagerMapBuffer.getOrCreateSpannableForText(
            view.getContext(), attributedString, mReactTextViewManagerCallback);
    view.setSpanned(spanned);

    int textBreakStrategy =
        TextAttributeProps.getTextBreakStrategy(
            paragraphAttributes.getString(TextLayoutManagerMapBuffer.PA_KEY_TEXT_BREAK_STRATEGY));
    int currentJustificationMode =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? 0 : view.getJustificationMode();

    return new ReactTextUpdate(
        spanned,
        -1, // UNUSED FOR TEXT
        false, // TODO add this into local Data
        TextAttributeProps.getTextAlignment(
            props, TextLayoutManagerMapBuffer.isRTL(attributedString), view.getGravityHorizontal()),
        textBreakStrategy,
        TextAttributeProps.getJustificationMode(props, currentJustificationMode));
  }

  @Override
  public @Nullable Map getExportedCustomDirectEventTypeConstants() {
    @Nullable
    Map<String, Object> baseEventTypeConstants = super.getExportedCustomDirectEventTypeConstants();
    Map<String, Object> eventTypeConstants =
        baseEventTypeConstants == null ? new HashMap<String, Object>() : baseEventTypeConstants;
    eventTypeConstants.putAll(
        MapBuilder.of(
            "topTextLayout", MapBuilder.of("registrationName", "onTextLayout"),
            "topInlineViewLayout", MapBuilder.of("registrationName", "onInlineViewLayout")));
    return eventTypeConstants;
  }

  @Override
  public long measure(
      Context context,
      ReadableMap localData,
      ReadableMap props,
      ReadableMap state,
      float width,
      YogaMeasureMode widthMode,
      float height,
      YogaMeasureMode heightMode,
      @Nullable float[] attachmentsPositions) {
    return TextLayoutManager.measureText(
        context,
        localData,
        props,
        width,
        widthMode,
        height,
        heightMode,
        mReactTextViewManagerCallback,
        attachmentsPositions);
  }

  @Override
  public long measure(
      Context context,
      MapBuffer localData,
      MapBuffer props,
      @Nullable MapBuffer state,
      float width,
      YogaMeasureMode widthMode,
      float height,
      YogaMeasureMode heightMode,
      @Nullable float[] attachmentsPositions) {
    return TextLayoutManagerMapBuffer.measureText(
        context,
        localData,
        props,
        width,
        widthMode,
        height,
        heightMode,
        mReactTextViewManagerCallback,
        attachmentsPositions);
  }

  @Override
  public void setPadding(ReactTextView view, int left, int top, int right, int bottom) {
    view.setPadding(left, top, right, bottom);
  }

  @ReactProp(name = ViewProps.FONT_SIZE, defaultFloat = ViewDefaults.FONT_SIZE_SP)
  public void setFontSize(ReactTextView view, float fontSize) {
    view.setFontSize(fontSize);
  }


  @ReactProp(name = ViewProps.FONT_FAMILY)
  public void setFontFamily(ReactTextView view, String fontFamily) {
    view.setFontFamily(fontFamily);
  }

  @ReactProp(name = ViewProps.MAX_FONT_SIZE_MULTIPLIER, defaultFloat = Float.NaN)
  public void setMaxFontSizeMultiplier(ReactTextView view, float maxFontSizeMultiplier) {
    view.setMaxFontSizeMultiplier(maxFontSizeMultiplier);
  }

  @ReactProp(name = ViewProps.FONT_WEIGHT)
  public void setFontWeight(ReactTextView view, @Nullable String fontWeight) {
    view.setFontWeight(fontWeight);
  }

  @ReactProp(name = ViewProps.FONT_STYLE)
  public void setFontStyle(ReactTextView view, @Nullable String fontStyle) {
    view.setFontStyle(fontStyle);
  }

  @ReactProp(name = ViewProps.FONT_VARIANT)
  public void setFontVariant(ReactTextView view, @Nullable ReadableArray fontVariant) {
    view.setFontFeatureSettings(ReactTypefaceUtils.parseFontVariant(fontVariant));
  }

  @ReactProp(name = ViewProps.INCLUDE_FONT_PADDING, defaultBoolean = true)
  public void setIncludeFontPadding(ReactTextView view, boolean includepad) {
    view.setIncludeFontPadding(includepad);
  }

  @ReactProp(name = "importantForAutofill")
  public void setImportantForAutofill(ReactTextView view, @Nullable String value) {
    int mode = View.IMPORTANT_FOR_AUTOFILL_AUTO;
    if ("no".equals(value)) {
      mode = View.IMPORTANT_FOR_AUTOFILL_NO;
    } else if ("noExcludeDescendants".equals(value)) {
      mode = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;
    } else if ("yes".equals(value)) {
      mode = View.IMPORTANT_FOR_AUTOFILL_YES;
    } else if ("yesExcludeDescendants".equals(value)) {
      mode = View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS;
    }
    setImportantForAutofill(view, mode);
  }

  private void setImportantForAutofill(ReactTextView view, int mode) {
    // Autofill hints were added in Android API 26.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }
    view.setImportantForAutofill(mode);
  }

  private void setAutofillHints(ReactTextView view, String... hints) {
    // Autofill hints were added in Android API 26.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }
    view.setAutofillHints(hints);
  }

  @ReactProp(name = ViewProps.LETTER_SPACING, defaultFloat = 0)
  public void setLetterSpacing(ReactTextView view, float letterSpacing) {
    view.setLetterSpacingPt(letterSpacing);
  }

  @ReactProp(name = ViewProps.ALLOW_FONT_SCALING, defaultBoolean = true)
  public void setAllowFontScaling(ReactTextView view, boolean allowFontScaling) {
    view.setAllowFontScaling(allowFontScaling);
  }

  @ReactProp(name = ViewProps.COLOR, customType = "Color")
  public void setColor(ReactTextView view, @Nullable Integer color) {
    if (color == null) {
      ColorStateList defaultContextTextColor =
              DefaultStyleValuesUtil.getDefaultTextColor(view.getContext());

      if (defaultContextTextColor != null) {
        view.setTextColor(defaultContextTextColor);
      } else {
        Context c = view.getContext();
        ReactSoftExceptionLogger.logSoftException(
                "ReactTextViewManager",
                new IllegalStateException(
                        "Could not get default text color from View Context: "
                                + (c != null ? c.getClass().getCanonicalName() : "null")));
      }
    } else {
      view.setTextColor(color);
    }
  }

  @ReactProp(name = "underlineColorAndroid", customType = "Color")
  public void setUnderlineColor(ReactTextView view, @Nullable Integer underlineColor) {
    // Drawable.mutate() can sometimes crash due to an AOSP bug:
    // See https://code.google.com/p/android/issues/detail?id=191754 for more info
    Drawable background = view.getBackground();
    Drawable drawableToMutate = background;

    if (background == null) {
      return;
    }

    if (background.getConstantState() != null) {
      try {
        drawableToMutate = background.mutate();
      } catch (NullPointerException e) {
        FLog.e(TAG, "NullPointerException when setting underlineColorAndroid for TextInput", e);
      }
    }

    if (underlineColor == null) {
      drawableToMutate.clearColorFilter();
    } else {
      // fixes underlineColor transparent not working on API 21
      // re-sets the TextInput underlineColor https://bit.ly/3M4alr6
      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
        int bottomBorderColor = view.getBorderColor(Spacing.BOTTOM);
        setBorderColor(view, Spacing.START, underlineColor);
        drawableToMutate.setColorFilter(underlineColor, PorterDuff.Mode.SRC_IN);
        setBorderColor(view, Spacing.START, bottomBorderColor);
      } else {
        drawableToMutate.setColorFilter(underlineColor, PorterDuff.Mode.SRC_IN);
      }
    }
  }

  @ReactProp(name = ViewProps.NUMBER_OF_LINES, defaultInt = 1)
  public void setNumLines(ReactTextView view, int numLines) {
    view.setLines(numLines);
  }

  @ReactProp(name = "borderStyle")
  public void setBorderStyle(ReactTextView view, @Nullable String borderStyle) {
    view.setBorderStyle(borderStyle);
  }

  @ReactProp(name = ViewProps.TEXT_DECORATION_LINE)
  public void setTextDecorationLine(ReactTextView view, @Nullable String textDecorationLineString) {
    view.setPaintFlags(
            view.getPaintFlags() & ~(Paint.STRIKE_THRU_TEXT_FLAG | Paint.UNDERLINE_TEXT_FLAG));

    for (String token : textDecorationLineString.split(" ")) {
      if (token.equals("underline")) {
        view.setPaintFlags(view.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
      } else if (token.equals("line-through")) {
        view.setPaintFlags(view.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
      }
    }
  }

  @ReactPropGroup(
          names = {
            ViewProps.BORDER_WIDTH,
            ViewProps.BORDER_LEFT_WIDTH,
            ViewProps.BORDER_RIGHT_WIDTH,
            ViewProps.BORDER_TOP_WIDTH,
            ViewProps.BORDER_BOTTOM_WIDTH,
          },
          defaultFloat = YogaConstants.UNDEFINED)
  public void setBorderWidth(ReactTextView view, int index, float width) {
    if (!YogaConstants.isUndefined(width)) {
      width = PixelUtil.toPixelFromDIP(width);
    }
    view.setBorderWidth(SPACING_TYPES[index], width);
  }

  @ReactPropGroup(
          names = {
                  "borderColor",
                  "borderLeftColor",
                  "borderRightColor",
                  "borderTopColor",
                  "borderBottomColor"
          },
          customType = "Color")
  public void setBorderColor(ReactTextView view, int index, Integer color) {
    float rgbComponent =
            color == null ? YogaConstants.UNDEFINED : (float) ((int) color & 0x00FFFFFF);
    float alphaComponent = color == null ? YogaConstants.UNDEFINED : (float) ((int) color >>> 24);
    view.setBorderColor(SPACING_TYPES[index], rgbComponent, alphaComponent);
  }


}
