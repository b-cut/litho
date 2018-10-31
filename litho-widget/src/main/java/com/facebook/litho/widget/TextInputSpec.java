/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.widget;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.view.View.TEXT_ALIGNMENT_GRAVITY;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentLayout;
import com.facebook.litho.Diff;
import com.facebook.litho.EventHandler;
import com.facebook.litho.Size;
import com.facebook.litho.SizeSpec;
import com.facebook.litho.StateValue;
import com.facebook.litho.annotations.FromTrigger;
import com.facebook.litho.annotations.MountSpec;
import com.facebook.litho.annotations.OnCreateInitialState;
import com.facebook.litho.annotations.OnCreateMountContent;
import com.facebook.litho.annotations.OnMeasure;
import com.facebook.litho.annotations.OnMount;
import com.facebook.litho.annotations.OnTrigger;
import com.facebook.litho.annotations.OnUnmount;
import com.facebook.litho.annotations.OnUpdateState;
import com.facebook.litho.annotations.Prop;
import com.facebook.litho.annotations.PropDefault;
import com.facebook.litho.annotations.ResType;
import com.facebook.litho.annotations.ShouldUpdate;
import com.facebook.litho.annotations.State;
import com.facebook.litho.utils.MeasureUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Component that renders a single-line text input using an EditText. The field operates as an
 * "uncontrolled" component, meaning there is no prop or state value that tracks the current text of
 * the field. You are responsible for tracking the current value by listening to TextChangedEvent;
 * to change the current value, use the SetTextEvent trigger to imperatively change the current
 * text.
 *
 * <p>Performance is critical for good user experience. Follow these tips for good performance:
 *
 * <ul>
 *   <li>Ensure you use Litho's setRootAsync to avoid any UI thread component operations.
 *   <li>Avoid changing props at all costs as it forces expensive EditText reconfiguration.
 *   <li>If using custom inputFilters, take special care to implement equals correctly or the text
 *       field must be reconfigured on every mount. (Better yet, store your InputFilter in a static
 *       or LruCache so that you're not constantly creating new instances.)
 * </ul>
 *
 * @prop initialText Initial text to display. If set, the value is set on the EditText exactly once:
 *     on initial mount. From then on, the EditText's text property is not modified.
 * @prop hint Hint text to display.
 * @prop shadowRadius Blur radius of the shadow.
 * @prop inputBackground The background of the EditText itself; this is subtly distinct from the
 *     Litho background prop. The padding of the inputBackground drawable will be applied to the
 *     EditText itself, insetting the cursor and text field.
 * @prop shadowRadius Blur radius of the shadow.
 * @prop shadowDx Horizontal offset of the shadow.
 * @prop shadowDy Vertical offset of the shadow.
 * @prop shadowColor Color for the shadow underneath the text.
 * @prop textColorStateList ColorStateList of the text.
 * @prop hintTextColorStateList ColorStateList of the hint text.
 * @prop textSize Size of the text.
 * @prop typeface Typeface for the text.
 * @prop textAlignment Alignment of the text within its container. This only has effect on API level
 *     17 and above; it's up to you to handle earlier API levels by adjusting gravity.
 * @prop gravity Gravity for the text within its container.
 * @prop editable If set, allows the text to be editable.
 * @prop inputType Type of data being placed in a text field, used to help an input method decide
 *     how to let the user enter text.
 * @prop imeOptions Type of data in the text field, reported to an IME when it has focus.
 * @prop inputFilters Used to filter the input to e.g. a max character count.
 */
@MountSpec(
  isPureRender = true,
  events = {
    TextChangedEvent.class,
    SelectionChangedEvent.class,
    KeyUpEvent.class,
    EditorActionEvent.class,
    SetTextEvent.class
  }
)
class TextInputSpec {
  @PropDefault
  protected static final ColorStateList textColorStateList = ColorStateList.valueOf(Color.BLACK);

  @PropDefault
  protected static final ColorStateList hintColorStateList = ColorStateList.valueOf(Color.LTGRAY);

  @PropDefault static final CharSequence hint = "";
  @PropDefault static final CharSequence initialText = "";
  @PropDefault protected static final int shadowColor = Color.GRAY;
  @PropDefault protected static final int textSize = 13;
  @PropDefault protected static final Typeface typeface = Typeface.DEFAULT;
  @PropDefault protected static final int textAlignment = TEXT_ALIGNMENT_GRAVITY;
  @PropDefault protected static final int gravity = Gravity.CENTER_VERTICAL | Gravity.START;
  @PropDefault protected static final boolean editable = true;
  @PropDefault protected static final int inputType = EditorInfo.TYPE_CLASS_TEXT;
  @PropDefault protected static final int imeOptions = EditorInfo.IME_NULL;

  /** UI thread only; used in OnMount. */
  private static final Rect sBackgroundPaddingRect = new Rect();
  /** UI thread only; used in OnMount. */
  private static final InputFilter[] NO_FILTERS = new InputFilter[0];

  @OnCreateInitialState
  static void onCreateInitialState(
      final ComponentContext c,
      StateValue<AtomicReference<EditTextWithEventHandlers>> mountedView,
      StateValue<AtomicReference<CharSequence>> savedText,
      StateValue<Integer> measureSeqNumber,
      @Prop(optional = true, resType = ResType.STRING) CharSequence initialText) {
    mountedView.set(new AtomicReference<EditTextWithEventHandlers>());
    measureSeqNumber.set(0);
    savedText.set(new AtomicReference<>(initialText));
  }

  @OnMeasure
  static void onMeasure(
      ComponentContext c,
      ComponentLayout layout,
      int widthSpec,
      int heightSpec,
      Size size,
      @Prop(optional = true, resType = ResType.STRING) CharSequence hint,
      @Prop(optional = true, resType = ResType.DRAWABLE) Drawable inputBackground,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowRadius,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowDx,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowDy,
      @Prop(optional = true, resType = ResType.COLOR) int shadowColor,
      @Prop(optional = true) ColorStateList textColorStateList,
      @Prop(optional = true) ColorStateList hintColorStateList,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) int textSize,
      @Prop(optional = true) Typeface typeface,
      @Prop(optional = true) int textAlignment,
      @Prop(optional = true) int gravity,
      @Prop(optional = true) boolean editable,
      @Prop(optional = true) int inputType,
      @Prop(optional = true) int imeOptions,
      @Prop(optional = true, varArg = "inputFilter") List<InputFilter> inputFilters,
      @State AtomicReference<CharSequence> savedText,
      @State int measureSeqNumber) {
    // For width we always take all available space, or collapse to 0 if unspecified.
    if (SizeSpec.getMode(widthSpec) == SizeSpec.UNSPECIFIED) {
      size.width = 0;
    } else {
      size.width = SizeSpec.getSize(widthSpec);
    }

    // The height should be the measured height of EditText with relevant params
    final EditText forMeasure = new EditText(c);
    setParams(
        forMeasure,
        hint,
        getBackgroundOrDefault(c, inputBackground),
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
        textColorStateList,
        hintColorStateList,
        textSize,
        typeface,
        textAlignment,
        gravity,
        editable,
        inputType,
        imeOptions,
        inputFilters,
        // onMeasure happens:
        // 1. After initState before onMount: savedText = initText.
        // 2. After onMount before onUnmount: savedText preserved from underlying editText.
        savedText.get());
    forMeasure.measure(
        MeasureUtils.getViewMeasureSpec(widthSpec), MeasureUtils.getViewMeasureSpec(heightSpec));

    size.height = forMeasure.getMeasuredHeight();
  }

  private static void setParams(
      EditText editText,
      @Nullable CharSequence hint,
      @Nullable Drawable background,
      float shadowRadius,
      float shadowDx,
      float shadowDy,
      int shadowColor,
      ColorStateList textColorStateList,
      ColorStateList hintColorStateList,
      int textSize,
      Typeface typeface,
      int textAlignment,
      int gravity,
      boolean editable,
      int inputType,
      int imeOptions,
      @Nullable List<InputFilter> inputFilters,
      @Nullable CharSequence text) {

    // TODO: T33972982 For multiline add EditorInfo.TYPE_CLASS_TEXT
    editText.setInputType(inputType & ~EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
    editText.setLines(1);

    // Needs to be set before the text so it would apply to the current text
    if (inputFilters != null) {
      editText.setFilters(inputFilters.toArray(new InputFilter[inputFilters.size()]));
    } else {
      editText.setFilters(NO_FILTERS);
    }
    editText.setHint(hint);

    if (SDK_INT < JELLY_BEAN) {
      editText.setBackgroundDrawable(background);
    } else {
      editText.setBackground(background);
    }
    // From the docs for setBackground:
    // "If the background has padding, this View's padding is set to the background's padding.
    // However, when a background is removed, this View's padding isn't touched. If setting the
    // padding is desired, please use setPadding."
    if (background == null || !background.getPadding(sBackgroundPaddingRect)) {
      editText.setPadding(0, 0, 0, 0);
    }
    editText.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);
    editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    editText.setTypeface(typeface, 0);
    editText.setGravity(gravity);
    editText.setImeOptions(imeOptions);
    editText.setFocusable(editable);
    editText.setFocusableInTouchMode(editable);
    editText.setClickable(editable);
    editText.setLongClickable(editable);
    editText.setCursorVisible(editable);
    editText.setTextColor(textColorStateList);
    editText.setHintTextColor(hintColorStateList);
    if (SDK_INT >= JELLY_BEAN_MR1) {
      editText.setTextAlignment(textAlignment);
    }
    if (text != null && !TextInputSpec.equals(editText.getText().toString(), text.toString())) {
      editText.setText(text);
    }
  }

  @ShouldUpdate
  static boolean shouldUpdate(
      @Prop(optional = true, resType = ResType.STRING) Diff<CharSequence> initialText,
      @Prop(optional = true, resType = ResType.STRING) Diff<CharSequence> hint,
      @Prop(optional = true, resType = ResType.DRAWABLE) Diff<Drawable> inputBackground,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) Diff<Float> shadowRadius,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) Diff<Float> shadowDx,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) Diff<Float> shadowDy,
      @Prop(optional = true, resType = ResType.COLOR) Diff<Integer> shadowColor,
      @Prop(optional = true) Diff<ColorStateList> textColorStateList,
      @Prop(optional = true) Diff<ColorStateList> hintColorStateList,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) Diff<Integer> textSize,
      @Prop(optional = true) Diff<Typeface> typeface,
      @Prop(optional = true) Diff<Integer> textAlignment,
      @Prop(optional = true) Diff<Integer> gravity,
      @Prop(optional = true) Diff<Boolean> editable,
      @Prop(optional = true) Diff<Integer> inputType,
      @Prop(optional = true) Diff<Integer> imeOptions,
      @Prop(optional = true, varArg = "inputFilter") Diff<List<InputFilter>> inputFilters,
      @State Diff<Integer> measureSeqNumber) {
    if (!equals(measureSeqNumber.getPrevious(), measureSeqNumber.getNext())) {
      return true;
    }
    if (!equals(initialText.getPrevious(), initialText.getNext())) {
      return true;
    }
    if (!equals(hint.getPrevious(), hint.getNext())) {
      return true;
    }
    if (!equals(shadowRadius.getPrevious(), shadowRadius.getNext())) {
      return true;
    }
    if (!equals(shadowDx.getPrevious(), shadowDx.getNext())) {
      return true;
    }
    if (!equals(shadowDy.getPrevious(), shadowDy.getNext())) {
      return true;
    }
    if (!equals(shadowColor.getPrevious(), shadowColor.getNext())) {
      return true;
    }
    if (!equals(textColorStateList.getPrevious(), textColorStateList.getNext())) {
      return true;
    }
    if (!equals(hintColorStateList.getPrevious(), hintColorStateList.getNext())) {
      return true;
    }
    if (!equals(textSize.getPrevious(), textSize.getNext())) {
      return true;
    }
    if (!equals(typeface.getPrevious(), typeface.getNext())) {
      return true;
    }
    if (!equals(textAlignment.getPrevious(), textAlignment.getNext())) {
      return true;
    }
    if (!equals(gravity.getPrevious(), gravity.getNext())) {
      return true;
    }
    if (!equals(editable.getPrevious(), editable.getNext())) {
      return true;
    }
    if (!equals(inputType.getPrevious(), inputType.getNext())) {
      return true;
    }
    if (!equals(imeOptions.getPrevious(), imeOptions.getNext())) {
      return true;
    }
    if (!equalInputFilters(inputFilters.getPrevious(), inputFilters.getNext())) {
      return true;
    }
    // Save the nastiest for last: trying to diff drawables.
    Drawable previousBackground = inputBackground.getPrevious();
    Drawable nextBackground = inputBackground.getNext();
    if (previousBackground == null && nextBackground != null) {
      return true;
    } else if (previousBackground != null && nextBackground == null) {
      return true;
    } else if (previousBackground != null && nextBackground != null) {
      if (previousBackground instanceof ColorDrawable && nextBackground instanceof ColorDrawable) {
        // This doesn't account for tint list/mode (no way to get that information)
        // and doesn't account for color filter (fine since ColorDrawable ignores it anyway).
        ColorDrawable prevColor = (ColorDrawable) previousBackground;
        ColorDrawable nextColor = (ColorDrawable) nextBackground;
        if (prevColor.getColor() != nextColor.getColor()) {
          return true;
        }
      } else {
        // The best we can do here is compare getConstantState. This can result in spurious updates;
        // they might be different objects representing the same drawable. But it's the best we can
        // do without actually comparing bitmaps (which is too expensive).
        if (!equals(previousBackground.getConstantState(), nextBackground.getConstantState())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean equals(Object a, Object b) {
    return (a == null) ? b == null : a.equals(b);
  }

  /** LengthFilter and AllCaps do not implement isEqual. Correct for the deficiency. */
  private static boolean equalInputFilters(List<InputFilter> a, List<InputFilter> b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      InputFilter fa = a.get(i);
      InputFilter fb = b.get(i);
      if (fa instanceof InputFilter.AllCaps && fb instanceof InputFilter.AllCaps) {
        continue; // equal, AllCaps has no configuration
      }
      if (SDK_INT >= LOLLIPOP) { // getMax added in lollipop
        if (fa instanceof InputFilter.LengthFilter && fb instanceof InputFilter.LengthFilter) {
          if (((InputFilter.LengthFilter) fa).getMax()
              != ((InputFilter.LengthFilter) fb).getMax()) {
            return false;
          }
          continue; // equal, same max
        }
      }
      // Best we can do in this case is call equals().
      if (!equals(fa, fb)) {
        return false;
      }
    }
    return true;
  }

  @OnCreateMountContent
  protected static EditTextWithEventHandlers onCreateMountContent(Context c) {
    return new EditTextWithEventHandlers(c);
  }

  @OnMount
  static void onMount(
      final ComponentContext c,
      EditTextWithEventHandlers editText,
      @Prop(optional = true, resType = ResType.STRING) CharSequence hint,
      @Prop(optional = true, resType = ResType.DRAWABLE) Drawable inputBackground,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowRadius,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowDx,
      @Prop(optional = true, resType = ResType.DIMEN_OFFSET) float shadowDy,
      @Prop(optional = true, resType = ResType.COLOR) int shadowColor,
      @Prop(optional = true) ColorStateList textColorStateList,
      @Prop(optional = true) ColorStateList hintColorStateList,
      @Prop(optional = true, resType = ResType.DIMEN_TEXT) int textSize,
      @Prop(optional = true) Typeface typeface,
      @Prop(optional = true) int textAlignment,
      @Prop(optional = true) int gravity,
      @Prop(optional = true) boolean editable,
      @Prop(optional = true) int inputType,
      @Prop(optional = true) int imeOptions,
      @Prop(optional = true, varArg = "inputFilter") List<InputFilter> inputFilters,
      @State AtomicReference<CharSequence> savedText,
      @State AtomicReference<EditTextWithEventHandlers> mountedView) {
    mountedView.set(editText);

    setParams(
        editText,
        hint,
        getBackgroundOrDefault(c, inputBackground),
        shadowRadius,
        shadowDx,
        shadowDy,
        shadowColor,
        textColorStateList,
        hintColorStateList,
        textSize,
        typeface,
        textAlignment,
        gravity,
        editable,
        inputType,
        imeOptions,
        inputFilters,
        // onMount happens:
        // 1. After initState: savedText = initText.
        // 2. After onUnmount: savedText preserved from underlying editText.
        savedText.get());
    editText.setComponentContext(c);
    editText.setTextState(savedText);
    editText.setTextChangedEventHandler(TextInput.getTextChangedEventHandler(c));
    editText.setSelectionChangedEventHandler(TextInput.getSelectionChangedEventHandler(c));
    editText.setKeyUpEventHandler(TextInput.getKeyUpEventHandler(c));
    editText.setEditorActionEventHandler(TextInput.getEditorActionEventHandler(c));
  }

  @OnUnmount
  static void onUnmount(
      ComponentContext c,
      EditTextWithEventHandlers editText,
      @State AtomicReference<EditTextWithEventHandlers> mountedView) {
    editText.setComponentContext(null);
    editText.setTextState(null);
    editText.setTextChangedEventHandler(null);
    editText.setSelectionChangedEventHandler(null);
    editText.setKeyUpEventHandler(null);
    editText.setEditorActionEventHandler(null);
    mountedView.set(null);
  }

  @Nullable
  static Drawable getBackgroundOrDefault(ComponentContext c, Drawable specifiedBackground) {
    if (specifiedBackground != null) {
      return specifiedBackground;
    }
    final int[] attrs = {android.R.attr.background};
    TypedArray a =
        c.getBaseContext().obtainStyledAttributes(null, attrs, android.R.attr.editTextStyle, 0);
    Drawable defaultBackground = a.getDrawable(0);
    a.recycle();
    return defaultBackground;
  }

  @OnTrigger(RequestFocusEvent.class)
  static void requestFocus(
      ComponentContext c, @State AtomicReference<EditTextWithEventHandlers> mountedView) {
    EditTextWithEventHandlers view = mountedView.get();
    if (view != null) {
      if (view.requestFocus()) {
        InputMethodManager imm =
            (InputMethodManager) c.getBaseContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(view, 0);
      }
    }
  }

  @OnTrigger(ClearFocusEvent.class)
  static void clearFocus(
      ComponentContext c, @State AtomicReference<EditTextWithEventHandlers> mountedView) {
    EditTextWithEventHandlers view = mountedView.get();
    if (view != null) {
      view.clearFocus();
      InputMethodManager imm =
          (InputMethodManager) c.getBaseContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
  }

  @OnTrigger(SetTextEvent.class)
  static void setText(
      ComponentContext c,
      @State AtomicReference<EditTextWithEventHandlers> mountedView,
      @State AtomicReference<CharSequence> savedText,
      @FromTrigger String text) {
    EditTextWithEventHandlers view = mountedView.get();
    if (view != null) {
      // If line count changes state update will be triggered by view
      view.setText(text);
    } else {
      savedText.set(text);
      com.facebook.litho.widget.TextInput.remeasureForUpdatedText(c);
    }
  }

  @OnUpdateState
  static void remeasureForUpdatedText(StateValue<Integer> measureSeqNumber) {
    measureSeqNumber.set(measureSeqNumber.get() + 1);
  }

  static class EditTextWithEventHandlers extends EditText
      implements EditText.OnEditorActionListener {
    private @Nullable EventHandler<TextChangedEvent> mTextChangedEventHandler;
    private @Nullable EventHandler<SelectionChangedEvent> mSelectionChangedEventHandler;
    private @Nullable EventHandler<KeyUpEvent> mKeyUpEventHandler;
    private @Nullable EventHandler<EditorActionEvent> mEditorActionEventHandler;
    private @Nullable ComponentContext mComponentContext;
    private @Nullable AtomicReference<CharSequence> mTextState;
    private int mLineCount = -1;
    private boolean mMeasured;

    public EditTextWithEventHandlers(Context context) {
      super(context);
      // Unfortunately we can't just override `void onEditorAction(int actionCode)` as that only
      // covers a subset of all cases where onEditorActionListener is invoked.
      this.setOnEditorActionListener(this);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
      super.onTextChanged(text, start, lengthBefore, lengthAfter);
      if (mTextChangedEventHandler != null) {
        TextInput.dispatchTextChangedEvent(
            mTextChangedEventHandler, EditTextWithEventHandlers.this, text.toString());
      }
      if (mTextState != null) {
        mTextState.set(text);
      }
      // Line count of changed text.
      int lineCount = getLineCount();
      if (mMeasured && mLineCount != lineCount && mComponentContext != null) {
        com.facebook.litho.widget.TextInput.remeasureForUpdatedText(mComponentContext);
      }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      mMeasured = true;
      // Line count of the current text.
      mLineCount = getLineCount();
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
      super.onSelectionChanged(selStart, selEnd);
      if (mSelectionChangedEventHandler != null) {
        TextInput.dispatchSelectionChangedEvent(mSelectionChangedEventHandler, selStart, selEnd);
      }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
      if (mKeyUpEventHandler != null) {
        return TextInput.dispatchKeyUpEvent(mKeyUpEventHandler, keyCode, event);
      }
      return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
      if (mEditorActionEventHandler != null) {
        return TextInput.dispatchEditorActionEvent(mEditorActionEventHandler, actionId, event);
      }
      return false;
    }

    void setTextChangedEventHandler(
        @Nullable EventHandler<TextChangedEvent> textChangedEventHandler) {
      mTextChangedEventHandler = textChangedEventHandler;
    }

    void setSelectionChangedEventHandler(
        @Nullable EventHandler<SelectionChangedEvent> selectionChangedEventHandler) {
      mSelectionChangedEventHandler = selectionChangedEventHandler;
    }

    void setKeyUpEventHandler(@Nullable EventHandler<KeyUpEvent> keyUpEventHandler) {
      mKeyUpEventHandler = keyUpEventHandler;
    }

    void setEditorActionEventHandler(
        @Nullable EventHandler<EditorActionEvent> editorActionEventHandler) {
      mEditorActionEventHandler = editorActionEventHandler;
    }

    /** Sets context for state update, when the text height has changed. */
    void setComponentContext(@Nullable ComponentContext componentContext) {
      mComponentContext = componentContext;
    }

    /** Sets reference to keep current text up to date. */
    void setTextState(@Nullable AtomicReference<CharSequence> savedText) {
      mTextState = savedText;
    }
  }
}
