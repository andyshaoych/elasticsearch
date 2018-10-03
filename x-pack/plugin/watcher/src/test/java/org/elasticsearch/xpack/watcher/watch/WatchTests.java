/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.watch;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.ScriptQueryBuilder;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.watcher.actions.ActionFactory;
import org.elasticsearch.xpack.core.watcher.actions.ActionRegistry;
import org.elasticsearch.xpack.core.watcher.actions.ActionStatus;
import org.elasticsearch.xpack.core.watcher.actions.ActionWrapper;
import org.elasticsearch.xpack.core.watcher.actions.throttler.ActionThrottler;
import org.elasticsearch.xpack.core.watcher.condition.ConditionFactory;
import org.elasticsearch.xpack.core.watcher.condition.ConditionRegistry;
import org.elasticsearch.xpack.core.watcher.condition.ExecutableCondition;
import org.elasticsearch.xpack.core.watcher.input.ExecutableInput;
import org.elasticsearch.xpack.core.watcher.transform.ExecutableTransform;
import org.elasticsearch.xpack.core.watcher.transform.TransformFactory;
import org.elasticsearch.xpack.core.watcher.transform.TransformRegistry;
import org.elasticsearch.xpack.core.watcher.transform.chain.ChainTransform;
import org.elasticsearch.xpack.core.watcher.transform.chain.ExecutableChainTransform;
import org.elasticsearch.xpack.core.watcher.trigger.Trigger;
import org.elasticsearch.xpack.core.watcher.watch.ClockMock;
import org.elasticsearch.xpack.core.watcher.watch.Watch;
import org.elasticsearch.xpack.core.watcher.watch.WatchField;
import org.elasticsearch.xpack.core.watcher.watch.WatchStatus;
import org.elasticsearch.xpack.watcher.actions.email.EmailAction;
import org.elasticsearch.xpack.watcher.actions.email.EmailActionFactory;
import org.elasticsearch.xpack.watcher.actions.email.ExecutableEmailAction;
import org.elasticsearch.xpack.watcher.actions.index.ExecutableIndexAction;
import org.elasticsearch.xpack.watcher.actions.index.IndexAction;
import org.elasticsearch.xpack.watcher.actions.index.IndexActionFactory;
import org.elasticsearch.xpack.watcher.actions.webhook.ExecutableWebhookAction;
import org.elasticsearch.xpack.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.xpack.watcher.actions.webhook.WebhookActionFactory;
import org.elasticsearch.xpack.watcher.common.http.HttpClient;
import org.elasticsearch.xpack.watcher.common.http.HttpMethod;
import org.elasticsearch.xpack.watcher.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.watcher.common.text.TextTemplate;
import org.elasticsearch.xpack.watcher.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.watcher.condition.AlwaysConditionTests;
import org.elasticsearch.xpack.watcher.condition.ArrayCompareCondition;
import org.elasticsearch.xpack.watcher.condition.CompareCondition;
import org.elasticsearch.xpack.watcher.condition.InternalAlwaysCondition;
import org.elasticsearch.xpack.watcher.condition.NeverCondition;
import org.elasticsearch.xpack.watcher.condition.ScriptCondition;
import org.elasticsearch.xpack.watcher.input.InputBuilders;
import org.elasticsearch.xpack.watcher.input.InputFactory;
import org.elasticsearch.xpack.watcher.input.InputRegistry;
import org.elasticsearch.xpack.watcher.input.none.ExecutableNoneInput;
import org.elasticsearch.xpack.watcher.input.search.ExecutableSearchInput;
import org.elasticsearch.xpack.watcher.input.search.SearchInput;
import org.elasticsearch.xpack.watcher.input.search.SearchInputFactory;
import org.elasticsearch.xpack.watcher.input.simple.ExecutableSimpleInput;
import org.elasticsearch.xpack.watcher.input.simple.SimpleInput;
import org.elasticsearch.xpack.watcher.input.simple.SimpleInputFactory;
import org.elasticsearch.xpack.watcher.notification.email.DataAttachment;
import org.elasticsearch.xpack.watcher.notification.email.EmailService;
import org.elasticsearch.xpack.watcher.notification.email.EmailTemplate;
import org.elasticsearch.xpack.watcher.notification.email.HtmlSanitizer;
import org.elasticsearch.xpack.watcher.notification.email.Profile;
import org.elasticsearch.xpack.watcher.notification.email.attachment.EmailAttachments;
import org.elasticsearch.xpack.watcher.notification.email.attachment.EmailAttachmentsParser;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateService;
import org.elasticsearch.xpack.watcher.test.WatcherTestUtils;
import org.elasticsearch.xpack.watcher.transform.script.ExecutableScriptTransform;
import org.elasticsearch.xpack.watcher.transform.script.ScriptTransform;
import org.elasticsearch.xpack.watcher.transform.script.ScriptTransformFactory;
import org.elasticsearch.xpack.watcher.transform.search.ExecutableSearchTransform;
import org.elasticsearch.xpack.watcher.transform.search.SearchTransform;
import org.elasticsearch.xpack.watcher.transform.search.SearchTransformFactory;
import org.elasticsearch.xpack.watcher.trigger.TriggerEngine;
import org.elasticsearch.xpack.watcher.trigger.TriggerService;
import org.elasticsearch.xpack.watcher.trigger.schedule.CronSchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.DailySchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.HourlySchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.MonthlySchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.Schedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleRegistry;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTriggerEngine;
import org.elasticsearch.xpack.watcher.trigger.schedule.WeeklySchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.YearlySchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.support.DayOfWeek;
import org.elasticsearch.xpack.watcher.trigger.schedule.support.Month;
import org.elasticsearch.xpack.watcher.trigger.schedule.support.MonthTimes;
import org.elasticsearch.xpack.watcher.trigger.schedule.support.WeekTimes;
import org.elasticsearch.xpack.watcher.trigger.schedule.support.YearTimes;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.searchInput;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.templateRequest;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.mock;

public class WatchTests extends ESTestCase {

    private ScriptService scriptService;
    private Client client;
    private HttpClient httpClient;
    private EmailService emailService;
    private TextTemplateEngine templateEngine;
    private HtmlSanitizer htmlSanitizer;
    private XPackLicenseState licenseState;
    private Logger logger;
    private Settings settings = Settings.EMPTY;
    private WatcherSearchTemplateService searchTemplateService;

    @Before
    public void init() throws Exception {
        scriptService = mock(ScriptService.class);
        client = mock(Client.class);
        httpClient = mock(HttpClient.class);
        emailService = mock(EmailService.class);
        templateEngine = mock(TextTemplateEngine.class);
        htmlSanitizer = mock(HtmlSanitizer.class);
        licenseState = mock(XPackLicenseState.class);
        logger = Loggers.getLogger(WatchTests.class);
        searchTemplateService = mock(WatcherSearchTemplateService.class);
    }

    public void testParserSelfGenerated() throws Exception {
        DateTime now = new DateTime(UTC);
        ClockMock clock = ClockMock.frozen();
        clock.setTime(now);
        TransformRegistry transformRegistry = transformRegistry();
        boolean includeStatus = randomBoolean();
        Schedule schedule = randomSchedule();
        Trigger trigger = new ScheduleTrigger(schedule);
        ScheduleRegistry scheduleRegistry = registry(schedule);
        TriggerEngine triggerEngine = new ParseOnlyScheduleTriggerEngine(Settings.EMPTY, scheduleRegistry, clock);
        TriggerService triggerService = new TriggerService(Settings.EMPTY, singleton(triggerEngine));

        ExecutableInput input = randomInput();
        InputRegistry inputRegistry = registry(input.type());

        ExecutableCondition condition = AlwaysConditionTests.randomCondition(scriptService);
        ConditionRegistry conditionRegistry = conditionRegistry();

        ExecutableTransform transform = randomTransform();

        List<ActionWrapper> actions = randomActions();
        ActionRegistry actionRegistry = registry(actions, conditionRegistry, transformRegistry);

        Map<String, Object> metadata = singletonMap("_key", "_val");

        Map<String, ActionStatus> actionsStatuses = new HashMap<>();
        for (ActionWrapper action : actions) {
            actionsStatuses.put(action.id(), new ActionStatus(now));
        }
        WatchStatus watchStatus = new WatchStatus(new DateTime(clock.millis()), unmodifiableMap(actionsStatuses));

        TimeValue throttlePeriod = randomBoolean() ? null : TimeValue.timeValueSeconds(randomIntBetween(5, 10000));

        Watch watch = new Watch("_name", trigger, input, condition, transform, throttlePeriod, actions, metadata, watchStatus, 1L);

        BytesReference bytes = BytesReference.bytes(jsonBuilder().value(watch));
        logger.info("{}", bytes.utf8ToString());
        WatchParser watchParser = new WatchParser(settings, triggerService, actionRegistry, inputRegistry, null, clock);

        Watch parsedWatch = watchParser.parse("_name", includeStatus, bytes, XContentType.JSON);

        if (includeStatus) {
            assertThat(parsedWatch.status(), equalTo(watchStatus));
        }
        assertThat(parsedWatch.trigger(), equalTo(trigger));
        assertThat(parsedWatch.input(), equalTo(input));
        assertThat(parsedWatch.condition(), equalTo(condition));
        if (throttlePeriod != null) {
            assertThat(parsedWatch.throttlePeriod().millis(), equalTo(throttlePeriod.millis()));
        }
        assertThat(parsedWatch.metadata(), equalTo(metadata));
        assertThat(parsedWatch.actions(), equalTo(actions));
    }

    public void testThatBothStatusFieldsCanBeRead() throws Exception {
        InputRegistry inputRegistry = mock(InputRegistry.class);
        ActionRegistry actionRegistry = mock(ActionRegistry.class);
        // a fake trigger service that advances past the trigger end object, which cannot be done with mocking
        TriggerService triggerService = new TriggerService(Settings.EMPTY, Collections.emptySet()) {
            @Override
            public Trigger parseTrigger(String jobName, XContentParser parser) throws IOException {
                XContentParser.Token token;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                }

                return new ScheduleTrigger(randomSchedule());
            }
        };

        DateTime now = new DateTime(UTC);
        ClockMock clock = ClockMock.frozen();
        clock.setTime(now);

        List<ActionWrapper> actions = randomActions();
        Map<String, ActionStatus> actionsStatuses = new HashMap<>();
        for (ActionWrapper action : actions) {
            actionsStatuses.put(action.id(), new ActionStatus(now));
        }
        WatchStatus watchStatus = new WatchStatus(new DateTime(clock.millis()), unmodifiableMap(actionsStatuses));

        WatchParser watchParser = new WatchParser(settings, triggerService, actionRegistry, inputRegistry, null, clock);
        XContentBuilder builder = jsonBuilder().startObject().startObject("trigger").endObject().field("status", watchStatus).endObject();
        Watch watch = watchParser.parse("foo", true, BytesReference.bytes(builder), XContentType.JSON);
        assertThat(watch.status().state().getTimestamp().getMillis(), is(clock.millis()));
        for (ActionWrapper action : actions) {
            assertThat(watch.status().actionStatus(action.id()), is(actionsStatuses.get(action.id())));
        }
    }

    public void testParserBadActions() throws Exception {
        ClockMock clock = ClockMock.frozen();
        ScheduleRegistry scheduleRegistry = registry(randomSchedule());
        TriggerEngine triggerEngine = new ParseOnlyScheduleTriggerEngine(Settings.EMPTY, scheduleRegistry, clock);
        TriggerService triggerService = new TriggerService(Settings.EMPTY, singleton(triggerEngine));
        ConditionRegistry conditionRegistry = conditionRegistry();
        ExecutableInput input = randomInput();
        InputRegistry inputRegistry = registry(input.type());

        TransformRegistry transformRegistry = transformRegistry();

        List<ActionWrapper> actions = randomActions();
        ActionRegistry actionRegistry = registry(actions,conditionRegistry, transformRegistry);


        XContentBuilder jsonBuilder = jsonBuilder()
                .startObject()
                    .startArray("actions").endArray()
                .endObject();
        WatchParser watchParser = new WatchParser(settings, triggerService, actionRegistry, inputRegistry, null, clock);
        try {
            watchParser.parse("failure", false, BytesReference.bytes(jsonBuilder), XContentType.JSON);
            fail("This watch should fail to parse as actions is an array");
        } catch (ElasticsearchParseException pe) {
            assertThat(pe.getMessage().contains("could not parse actions for watch [failure]"), is(true));
        }
    }

    public void testParserDefaults() throws Exception {
        Schedule schedule = randomSchedule();
        ScheduleRegistry scheduleRegistry = registry(schedule);
        TriggerEngine triggerEngine = new ParseOnlyScheduleTriggerEngine(Settings.EMPTY, scheduleRegistry, Clock.systemUTC());
        TriggerService triggerService = new TriggerService(Settings.EMPTY, singleton(triggerEngine));

        ConditionRegistry conditionRegistry = conditionRegistry();
        InputRegistry inputRegistry = registry(new ExecutableNoneInput(logger).type());
        TransformRegistry transformRegistry = transformRegistry();
        ActionRegistry actionRegistry = registry(Collections.emptyList(), conditionRegistry, transformRegistry);

        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        builder.startObject(WatchField.TRIGGER.getPreferredName())
                .field(ScheduleTrigger.TYPE, schedule(schedule).build())
                .endObject();
        builder.endObject();
        WatchParser watchParser = new WatchParser(settings, triggerService, actionRegistry, inputRegistry, null, Clock.systemUTC());
        Watch watch = watchParser.parse("failure", false, BytesReference.bytes(builder), XContentType.JSON);
        assertThat(watch, notNullValue());
        assertThat(watch.trigger(), instanceOf(ScheduleTrigger.class));
        assertThat(watch.input(), instanceOf(ExecutableNoneInput.class));
        assertThat(watch.condition(), instanceOf(InternalAlwaysCondition.class));
        assertThat(watch.transform(), nullValue());
        assertThat(watch.actions(), notNullValue());
        assertThat(watch.actions().size(), is(0));
    }

    public void testParseWatch_verifyScriptLangDefault() throws Exception {
        ScheduleRegistry scheduleRegistry = registry(new IntervalSchedule(new IntervalSchedule.Interval(1,
                IntervalSchedule.Interval.Unit.SECONDS)));
        TriggerEngine triggerEngine = new ParseOnlyScheduleTriggerEngine(Settings.EMPTY, scheduleRegistry, Clock.systemUTC());
        TriggerService triggerService = new TriggerService(Settings.EMPTY, singleton(triggerEngine));

        ConditionRegistry conditionRegistry = conditionRegistry();
        InputRegistry inputRegistry = registry(SearchInput.TYPE);
        TransformRegistry transformRegistry = transformRegistry();
        ActionRegistry actionRegistry = registry(Collections.emptyList(), conditionRegistry, transformRegistry);
        WatchParser watchParser = new WatchParser(settings, triggerService, actionRegistry, inputRegistry, null, Clock.systemUTC());

        WatcherSearchTemplateService searchTemplateService = new WatcherSearchTemplateService(settings, scriptService, xContentRegistry());

        XContentBuilder builder = jsonBuilder();
        builder.startObject();

        builder.startObject("trigger");
        builder.startObject("schedule");
        builder.field("interval", "99w");
        builder.endObject();
        builder.endObject();

        builder.startObject("input");
        builder.startObject("search");
        builder.startObject("request");
        builder.startObject("body");
        builder.startObject("query");
        builder.startObject("script");
        if (randomBoolean()) {
            builder.field("script", "return true");
        } else {
            builder.startObject("script");
            builder.field("source", "return true");
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
        builder.endObject();
        builder.endObject();
        builder.endObject();
        builder.endObject();

        builder.startObject("condition");
        if (randomBoolean()) {
            builder.field("script", "return true");
        } else {
            builder.startObject("script");
            builder.field("source", "return true");
            builder.endObject();
        }
        builder.endObject();

        builder.endObject();

        // parse in default mode:
        Watch watch = watchParser.parse("_id", false, BytesReference.bytes(builder), XContentType.JSON);
        assertThat(((ScriptCondition) watch.condition()).getScript().getLang(), equalTo(Script.DEFAULT_SCRIPT_LANG));
        WatcherSearchTemplateRequest request = ((SearchInput) watch.input().input()).getRequest();
        SearchRequest searchRequest = searchTemplateService.toSearchRequest(request);
        assertThat(((ScriptQueryBuilder) searchRequest.source().query()).script().getLang(), equalTo(Script.DEFAULT_SCRIPT_LANG));
    }

    private static Schedule randomSchedule() {
        String type = randomFrom(CronSchedule.TYPE, HourlySchedule.TYPE, DailySchedule.TYPE, WeeklySchedule.TYPE, MonthlySchedule.TYPE,
                YearlySchedule.TYPE, IntervalSchedule.TYPE);
        switch (type) {
            case CronSchedule.TYPE:
                return new CronSchedule("0/5 * * * * ? *");
            case HourlySchedule.TYPE:
                return HourlySchedule.builder().minutes(30).build();
            case DailySchedule.TYPE:
                return DailySchedule.builder().atNoon().build();
            case WeeklySchedule.TYPE:
                return WeeklySchedule.builder().time(WeekTimes.builder().on(DayOfWeek.FRIDAY).atMidnight()).build();
            case MonthlySchedule.TYPE:
                return MonthlySchedule.builder().time(MonthTimes.builder().on(1).atNoon()).build();
            case YearlySchedule.TYPE:
                return YearlySchedule.builder().time(YearTimes.builder().in(Month.JANUARY).on(1).atMidnight()).build();
            default:
                return new IntervalSchedule(IntervalSchedule.Interval.seconds(5));
        }
    }

    private static ScheduleRegistry registry(Schedule schedule) {
        Set<Schedule.Parser> parsers = new HashSet<>();
        switch (schedule.type()) {
            case CronSchedule.TYPE:
                parsers.add(new CronSchedule.Parser());
                return new ScheduleRegistry(parsers);
            case HourlySchedule.TYPE:
                parsers.add(new HourlySchedule.Parser());
                return new ScheduleRegistry(parsers);
            case DailySchedule.TYPE:
                parsers.add(new DailySchedule.Parser());
                return new ScheduleRegistry(parsers);
            case WeeklySchedule.TYPE:
                parsers.add(new WeeklySchedule.Parser());
                return new ScheduleRegistry(parsers);
            case MonthlySchedule.TYPE:
                parsers.add(new MonthlySchedule.Parser());
                return new ScheduleRegistry(parsers);
            case YearlySchedule.TYPE:
                parsers.add(new YearlySchedule.Parser());
                return new ScheduleRegistry(parsers);
            case IntervalSchedule.TYPE:
                parsers.add(new IntervalSchedule.Parser());
                return new ScheduleRegistry(parsers);
            default:
                throw new IllegalArgumentException("unknown schedule [" + schedule + "]");
        }
    }

    private ExecutableInput randomInput() {
        String type = randomFrom(SearchInput.TYPE, SimpleInput.TYPE);
        switch (type) {
            case SearchInput.TYPE:
                SearchInput searchInput = searchInput(WatcherTestUtils.templateRequest(searchSource(), "idx"))
                        .timeout(randomBoolean() ? null : timeValueSeconds(between(1, 10000)))
                        .build();
                return new ExecutableSearchInput(searchInput, logger, client, searchTemplateService, null);
            default:
                SimpleInput simpleInput = InputBuilders.simpleInput(singletonMap("_key", "_val")).build();
                return new ExecutableSimpleInput(simpleInput, logger);
        }
    }

    private InputRegistry registry(String inputType) {
        Map<String, InputFactory> parsers = new HashMap<>();
        switch (inputType) {
            case SearchInput.TYPE:
                parsers.put(SearchInput.TYPE, new SearchInputFactory(settings, client, xContentRegistry(), scriptService));
                return new InputRegistry(Settings.EMPTY, parsers);
            default:
                parsers.put(SimpleInput.TYPE, new SimpleInputFactory(settings));
                return new InputRegistry(Settings.EMPTY, parsers);
        }
    }



    private ConditionRegistry conditionRegistry() {
        Map<String, ConditionFactory> parsers = new HashMap<>();
        parsers.put(InternalAlwaysCondition.TYPE, (c, id, p) -> InternalAlwaysCondition.parse(id, p));
        parsers.put(NeverCondition.TYPE, (c, id, p) -> NeverCondition.parse(id, p));
        parsers.put(ArrayCompareCondition.TYPE, (c, id, p) -> ArrayCompareCondition.parse(c, id, p));
        parsers.put(CompareCondition.TYPE, (c, id, p) -> CompareCondition.parse(c, id, p));
        parsers.put(ScriptCondition.TYPE, (c, id, p) -> ScriptCondition.parse(scriptService, id, p));
        return new ConditionRegistry(parsers, ClockMock.frozen());
    }

    private ExecutableTransform randomTransform() {
        String type = randomFrom(ScriptTransform.TYPE, SearchTransform.TYPE, ChainTransform.TYPE);
        TimeValue timeout = randomBoolean() ? timeValueSeconds(between(1, 10000)) : null;
        DateTimeZone timeZone = randomBoolean() ? DateTimeZone.UTC : null;
        switch (type) {
            case ScriptTransform.TYPE:
                return new ExecutableScriptTransform(new ScriptTransform(mockScript("_script")), logger, scriptService);
            case SearchTransform.TYPE:
                SearchTransform transform = new SearchTransform(
                        templateRequest(searchSource()), timeout, timeZone);
                return new ExecutableSearchTransform(transform, logger, client, searchTemplateService, TimeValue.timeValueMinutes(1));
            default: // chain
                SearchTransform searchTransform = new SearchTransform(
                        templateRequest(searchSource()), timeout, timeZone);
                ScriptTransform scriptTransform = new ScriptTransform(mockScript("_script"));

                ChainTransform chainTransform = new ChainTransform(Arrays.asList(searchTransform, scriptTransform));
                return new ExecutableChainTransform(chainTransform, logger, Arrays.<ExecutableTransform>asList(
                        new ExecutableSearchTransform(new SearchTransform(
                                templateRequest(searchSource()), timeout, timeZone),
                                logger, client, searchTemplateService, TimeValue.timeValueMinutes(1)),
                        new ExecutableScriptTransform(new ScriptTransform(mockScript("_script")),
                            logger, scriptService)));
        }
    }

    private TransformRegistry transformRegistry() {
        Map<String, TransformFactory> factories = new HashMap<>();
        factories.put(ScriptTransform.TYPE, new ScriptTransformFactory(settings, scriptService));
        factories.put(SearchTransform.TYPE, new SearchTransformFactory(settings, client, xContentRegistry(), scriptService));
        return new TransformRegistry(unmodifiableMap(factories));
    }

    private List<ActionWrapper> randomActions() {
        List<ActionWrapper> list = new ArrayList<>();
        if (randomBoolean()) {
            EmailAction action = new EmailAction(EmailTemplate.builder().build(), null, null, Profile.STANDARD,
                    randomFrom(DataAttachment.JSON, DataAttachment.YAML), EmailAttachments.EMPTY_ATTACHMENTS);
            list.add(new ActionWrapper("_email_" + randomAlphaOfLength(8), randomThrottler(),
                    AlwaysConditionTests.randomCondition(scriptService), randomTransform(),
                    new ExecutableEmailAction(action, logger, emailService, templateEngine, htmlSanitizer, Collections.emptyMap())));
        }
        if (randomBoolean()) {
            DateTimeZone timeZone = randomBoolean() ? DateTimeZone.UTC : null;
            TimeValue timeout = randomBoolean() ? timeValueSeconds(between(1, 10000)) : null;
            WriteRequest.RefreshPolicy refreshPolicy = randomBoolean() ? null : randomFrom(WriteRequest.RefreshPolicy.values());
            IndexAction action = new IndexAction("_index", "_type", randomBoolean() ? "123" : null, null, timeout, timeZone,
                    refreshPolicy);
            list.add(new ActionWrapper("_index_" + randomAlphaOfLength(8), randomThrottler(),
                    AlwaysConditionTests.randomCondition(scriptService),  randomTransform(),
                    new ExecutableIndexAction(action, logger, client, TimeValue.timeValueSeconds(30),
                            TimeValue.timeValueSeconds(30))));
        }
        if (randomBoolean()) {
            HttpRequestTemplate httpRequest = HttpRequestTemplate.builder("test.host", randomIntBetween(8000, 9000))
                    .method(randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT))
                    .path(new TextTemplate("_url"))
                    .build();
            WebhookAction action = new WebhookAction(httpRequest);
            list.add(new ActionWrapper("_webhook_" + randomAlphaOfLength(8), randomThrottler(),
                    AlwaysConditionTests.randomCondition(scriptService), randomTransform(),
                    new ExecutableWebhookAction(action, logger, httpClient, templateEngine)));
        }
        return list;
    }

    private ActionRegistry registry(List<ActionWrapper> actions, ConditionRegistry conditionRegistry, TransformRegistry transformRegistry) {
        Map<String, ActionFactory> parsers = new HashMap<>();
        for (ActionWrapper action : actions) {
            switch (action.action().type()) {
                case EmailAction.TYPE:
                    parsers.put(EmailAction.TYPE, new EmailActionFactory(settings, emailService, templateEngine,
                            new EmailAttachmentsParser(Collections.emptyMap())));
                    break;
                case IndexAction.TYPE:
                    parsers.put(IndexAction.TYPE, new IndexActionFactory(settings, client));
                    break;
                case WebhookAction.TYPE:
                    parsers.put(WebhookAction.TYPE, new WebhookActionFactory(settings, httpClient, templateEngine));
                    break;
            }
        }
        return new ActionRegistry(unmodifiableMap(parsers), conditionRegistry, transformRegistry, Clock.systemUTC(), licenseState);
    }

    private ActionThrottler randomThrottler() {
        return new ActionThrottler(Clock.systemUTC(), randomBoolean() ? null : timeValueSeconds(randomIntBetween(1, 10000)),
                licenseState);
    }

    @Override
    protected NamedXContentRegistry xContentRegistry() {
        return new NamedXContentRegistry(Arrays.asList(
                new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField(MatchAllQueryBuilder.NAME), (p, c) ->
                        MatchAllQueryBuilder.fromXContent(p)),
                new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField(ScriptQueryBuilder.NAME), (p, c) ->
                        ScriptQueryBuilder.fromXContent(p))
                ));
    }

    public static class ParseOnlyScheduleTriggerEngine extends ScheduleTriggerEngine {

        public ParseOnlyScheduleTriggerEngine(Settings settings, ScheduleRegistry registry, Clock clock) {
            super(settings, registry, clock);
        }

        @Override
        public void start(Collection<Watch> jobs) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void add(Watch watch) {
        }

        @Override
        public void pauseExecution() {
        }

        @Override
        public boolean remove(String jobId) {
            return false;
        }
    }
}