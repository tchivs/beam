/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct;

import static org.apache.beam.runners.core.WindowMatchers.isSingleWindowedValue;
import static org.apache.beam.runners.core.WindowMatchers.isWindowedValue;
import static org.apache.beam.sdk.transforms.windowing.PaneInfo.NO_FIRING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Collections;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IncompatibleWindowException;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.NonMergingWindowFn;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.PaneInfo.Timing;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.transforms.windowing.WindowMappingFn;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableSet;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.hamcrest.Matchers;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link WindowEvaluatorFactory}. */
@RunWith(JUnit4.class)
public class WindowEvaluatorFactoryTest {
  private static final Instant EPOCH = new Instant(0);

  private PCollection<Long> input;
  private WindowEvaluatorFactory factory;

  @Mock private EvaluationContext evaluationContext;

  private BundleFactory bundleFactory;

  private WindowedValue<Long> valueInGlobalWindow =
      WindowedValues.timestampedValueInGlobalWindow(3L, new Instant(2L));

  private final PaneInfo intervalWindowPane = PaneInfo.createPane(false, false, Timing.LATE, 3, 2);
  private WindowedValue<Long> valueInIntervalWindow =
      WindowedValues.of(
          2L, new Instant(-10L), new IntervalWindow(new Instant(-100), EPOCH), intervalWindowPane);

  private IntervalWindow intervalWindow1 =
      new IntervalWindow(EPOCH, BoundedWindow.TIMESTAMP_MAX_VALUE);

  private IntervalWindow intervalWindow2 =
      new IntervalWindow(
          EPOCH.plus(Duration.standardDays(3)), EPOCH.plus(Duration.standardDays(6)));

  private final PaneInfo multiWindowPane = PaneInfo.createPane(false, true, Timing.ON_TIME, 3, 0);
  private WindowedValue<Long> valueInGlobalAndTwoIntervalWindows =
      WindowedValues.of(
          1L,
          EPOCH.plus(Duration.standardDays(3)),
          ImmutableList.of(GlobalWindow.INSTANCE, intervalWindow1, intervalWindow2),
          multiWindowPane);

  @Rule public TestPipeline p = TestPipeline.create().enableAbandonedNodeEnforcement(false);

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    input = p.apply(Create.of(1L, 2L, 3L));

    bundleFactory = ImmutableListBundleFactory.create();
    factory = new WindowEvaluatorFactory(evaluationContext);
  }

  @Test
  public void singleWindowFnSucceeds() throws Exception {
    Duration windowDuration = Duration.standardDays(7);
    Window<Long> transform = Window.into(FixedWindows.of(windowDuration));
    PCollection<Long> windowed = input.apply(transform);

    CommittedBundle<Long> inputBundle = createInputBundle();

    UncommittedBundle<Long> outputBundle = createOutputBundle(windowed);

    BoundedWindow firstSecondWindow = new IntervalWindow(EPOCH, EPOCH.plus(windowDuration));
    BoundedWindow thirdWindow = new IntervalWindow(EPOCH.minus(windowDuration), EPOCH);

    TransformResult<Long> result = runEvaluator(windowed, inputBundle);

    assertThat(Iterables.getOnlyElement(result.getOutputBundles()), Matchers.equalTo(outputBundle));
    CommittedBundle<Long> committed = outputBundle.commit(Instant.now());

    assertThat(
        committed.getElements(),
        containsInAnyOrder(
            // value in global window
            isSingleWindowedValue(3L, new Instant(2L), firstSecondWindow, NO_FIRING),

            // value in just interval window
            isSingleWindowedValue(2L, new Instant(-10L), thirdWindow, intervalWindowPane),

            // value in global window and two interval windows
            isSingleWindowedValue(
                1L, EPOCH.plus(Duration.standardDays(3)), firstSecondWindow, multiWindowPane),
            isSingleWindowedValue(
                1L, EPOCH.plus(Duration.standardDays(3)), firstSecondWindow, multiWindowPane),
            isSingleWindowedValue(
                1L, EPOCH.plus(Duration.standardDays(3)), firstSecondWindow, multiWindowPane)));
  }

  @Test
  public void multipleWindowsWindowFnSucceeds() throws Exception {
    Duration windowDuration = Duration.standardDays(6);
    Duration slidingBy = Duration.standardDays(3);
    Window<Long> transform = Window.into(SlidingWindows.of(windowDuration).every(slidingBy));
    PCollection<Long> windowed = input.apply(transform);

    CommittedBundle<Long> inputBundle = createInputBundle();
    UncommittedBundle<Long> outputBundle = createOutputBundle(windowed);

    TransformResult<Long> result = runEvaluator(windowed, inputBundle);

    assertThat(Iterables.getOnlyElement(result.getOutputBundles()), Matchers.equalTo(outputBundle));
    CommittedBundle<Long> committed = outputBundle.commit(Instant.now());

    BoundedWindow w1 = new IntervalWindow(EPOCH, EPOCH.plus(windowDuration));
    BoundedWindow w2 =
        new IntervalWindow(EPOCH.plus(slidingBy), EPOCH.plus(slidingBy).plus(windowDuration));
    BoundedWindow wMinus1 = new IntervalWindow(EPOCH.minus(windowDuration), EPOCH);
    BoundedWindow wMinusSlide =
        new IntervalWindow(EPOCH.minus(windowDuration).plus(slidingBy), EPOCH.plus(slidingBy));

    assertThat(
        committed.getElements(),
        containsInAnyOrder(
            // Value in global window mapped to one windowed value in multiple windows
            isWindowedValue(
                valueInGlobalWindow.getValue(),
                valueInGlobalWindow.getTimestamp(),
                ImmutableSet.of(w1, wMinusSlide),
                NO_FIRING),

            // Value in interval window mapped to one windowed value in multiple windows
            isWindowedValue(
                valueInIntervalWindow.getValue(),
                valueInIntervalWindow.getTimestamp(),
                ImmutableSet.of(wMinus1, wMinusSlide),
                valueInIntervalWindow.getPaneInfo()),

            // Value in three windows mapped to three windowed values in the same multiple windows
            isWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                ImmutableSet.of(w1, w2),
                valueInGlobalAndTwoIntervalWindows.getPaneInfo()),
            isWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                ImmutableSet.of(w1, w2),
                valueInGlobalAndTwoIntervalWindows.getPaneInfo()),
            isWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                ImmutableSet.of(w1, w2),
                valueInGlobalAndTwoIntervalWindows.getPaneInfo())));
  }

  @Test
  public void referencesEarlierWindowsSucceeds() throws Exception {
    Window<Long> transform = Window.into(new EvaluatorTestWindowFn());
    PCollection<Long> windowed = input.apply(transform);

    CommittedBundle<Long> inputBundle = createInputBundle();
    UncommittedBundle<Long> outputBundle = createOutputBundle(windowed);

    TransformResult<Long> result = runEvaluator(windowed, inputBundle);

    assertThat(Iterables.getOnlyElement(result.getOutputBundles()), Matchers.equalTo(outputBundle));
    CommittedBundle<Long> committed = outputBundle.commit(Instant.now());

    assertThat(
        committed.getElements(),
        containsInAnyOrder(
            // Value in global window mapped to [timestamp, timestamp+1)
            isSingleWindowedValue(
                valueInGlobalWindow.getValue(),
                valueInGlobalWindow.getTimestamp(),
                new IntervalWindow(
                    valueInGlobalWindow.getTimestamp(),
                    valueInGlobalWindow.getTimestamp().plus(Duration.millis(1L))),
                valueInGlobalWindow.getPaneInfo()),

            // Value in interval window mapped to the same window
            isWindowedValue(
                valueInIntervalWindow.getValue(),
                valueInIntervalWindow.getTimestamp(),
                valueInIntervalWindow.getWindows(),
                valueInIntervalWindow.getPaneInfo()),

            // Value in global window and two interval windows exploded and mapped in both ways
            isSingleWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                new IntervalWindow(
                    valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                    valueInGlobalAndTwoIntervalWindows.getTimestamp().plus(Duration.millis(1L))),
                valueInGlobalAndTwoIntervalWindows.getPaneInfo()),
            isSingleWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                intervalWindow1,
                valueInGlobalAndTwoIntervalWindows.getPaneInfo()),
            isSingleWindowedValue(
                valueInGlobalAndTwoIntervalWindows.getValue(),
                valueInGlobalAndTwoIntervalWindows.getTimestamp(),
                intervalWindow2,
                valueInGlobalAndTwoIntervalWindows.getPaneInfo())));
  }

  private CommittedBundle<Long> createInputBundle() {
    CommittedBundle<Long> inputBundle =
        bundleFactory
            .createBundle(input)
            .add(valueInGlobalWindow)
            .add(valueInGlobalAndTwoIntervalWindows)
            .add(valueInIntervalWindow)
            .commit(Instant.now());
    return inputBundle;
  }

  private UncommittedBundle<Long> createOutputBundle(PCollection<Long> output) {
    UncommittedBundle<Long> outputBundle = bundleFactory.createBundle(output);
    when(evaluationContext.createBundle(output)).thenReturn(outputBundle);
    return outputBundle;
  }

  private TransformResult<Long> runEvaluator(
      PCollection<Long> windowed, CommittedBundle<Long> inputBundle) throws Exception {
    TransformEvaluator<Long> evaluator =
        factory.forApplication(DirectGraphs.getProducer(windowed), inputBundle);

    evaluator.processElement(valueInGlobalWindow);
    evaluator.processElement(valueInGlobalAndTwoIntervalWindows);
    evaluator.processElement(valueInIntervalWindow);
    return evaluator.finishBundle();
  }

  private static class EvaluatorTestWindowFn extends NonMergingWindowFn<Long, BoundedWindow> {
    @Override
    public Collection<BoundedWindow> assignWindows(AssignContext c) throws Exception {
      if (c.window().equals(GlobalWindow.INSTANCE)) {
        return Collections.singleton(
            new IntervalWindow(c.timestamp(), c.timestamp().plus(Duration.millis(1L))));
      }
      return Collections.singleton(c.window());
    }

    @Override
    public boolean isCompatible(WindowFn<?, ?> other) {
      return false;
    }

    @Override
    public void verifyCompatibility(WindowFn<?, ?> other) throws IncompatibleWindowException {
      throw new IncompatibleWindowException(
          other,
          String.format(
              "%s is not compatible with any other %s.",
              EvaluatorTestWindowFn.class.getSimpleName(), WindowFn.class.getSimpleName()));
    }

    @Override
    public Coder<BoundedWindow> windowCoder() {
      @SuppressWarnings({"unchecked", "rawtypes"})
      Coder coder = (Coder) GlobalWindow.Coder.INSTANCE;
      return coder;
    }

    @Override
    public WindowMappingFn<BoundedWindow> getDefaultWindowMappingFn() {
      throw new UnsupportedOperationException("Cannot be used as a side input");
    }
  }
}
