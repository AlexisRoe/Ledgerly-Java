# Java Backend Resources

## Knowledge

- [The Java Tutorials: Variables](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/variables.html)
  Oracle's official tutorial: four kinds of variables, naming rules and conventions. Use for: declarations, naming.
- [The Java Tutorials: Primitive Data Types](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
  The 8 primitives, defaults, literals, String. Use for: choosing types.
- [The Java Tutorials: if-then and if-then-else](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/if.html)
  Use for: conditionals, braces.
- [The Java Tutorials: Relational and Conditional Operators](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/op2.html)
  Use for: ==, &&, ||, short-circuiting, ternary.
- [The Java Tutorials: for](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/for.html) and [while/do-while](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/while.html)
  Use for: loop syntax.
- [The Java Tutorials: switch](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/switch.html)
  Classic switch only (written for JDK 8). Use for: break and fall-through.
- [Oracle Java 21 Language Guide: Switch Expressions and Statements](https://docs.oracle.com/en/java/javase/21/language/switch-expressions-and-statements.html)
  Use for: arrow syntax, yield, exhaustiveness.
- [The Java Tutorials: Defining Methods](https://docs.oracle.com/javase/tutorial/java/javaOO/methods.html) and [Constructors](https://docs.oracle.com/javase/tutorial/java/javaOO/constructors.html)
  Use for: method anatomy, signatures, overloading, constructors, new.
- [Oracle Java 21 Language Guide: Record Classes](https://docs.oracle.com/en/java/javase/21/language/records.html)
  Use for: records, compact constructors, what is generated.
- [The Java Tutorials: Collection Interfaces](https://docs.oracle.com/javase/tutorial/collections/interfaces/index.html) and [Why Use Generics?](https://docs.oracle.com/javase/tutorial/java/generics/why.html)
  Use for: List, Set, Map, type arguments.
- [The Java Tutorials: Exceptions](https://docs.oracle.com/javase/tutorial/essential/exceptions/definition.html) ([catching](https://docs.oracle.com/javase/tutorial/essential/exceptions/catch.html), [unchecked](https://docs.oracle.com/javase/tutorial/essential/exceptions/runtime.html))
  Use for: throw/catch, checked vs unchecked.
- [The Java Tutorials: Creating and Using Interfaces](https://docs.oracle.com/javase/tutorial/java/IandI/createinterface.html)
  Use for: interfaces, implements. Default methods not yet covered.
- [The Java Tutorials: The finally Block](https://docs.oracle.com/javase/tutorial/essential/exceptions/finally.html) and [try-with-resources](https://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html)
  Use for: cleanup semantics, AutoCloseable.
- [Optional (Java 21 API)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html)
  Primary source for Optional methods and intended use.
- [Oracle Java 21 Language Guide: Sealed Classes](https://docs.oracle.com/en/java/javase/21/language/sealed-classes-and-interfaces.html), [Pattern Matching for instanceof](https://docs.oracle.com/en/java/javase/21/language/pattern-matching-instanceof-operator.html), [for switch](https://docs.oracle.com/en/java/javase/21/language/pattern-matching-switch.html)
  Use for: sealed/permits, binding variables, guards, dominance, exhaustiveness. (The overview page "pattern-matching.html" was only a chapter index.)
- [The Java Tutorials: Lambda Expressions](https://docs.oracle.com/javase/tutorial/java/javaOO/lambdaexpressions.html), [Method References](https://docs.oracle.com/javase/tutorial/java/javaOO/methodreferences.html), [Streams](https://docs.oracle.com/javase/tutorial/collections/streams/index.html)
  Use for: lambdas, functional interfaces, pipelines. The Streams page covers filter/mapToInt/average, not collect or toList.
- [Spring Framework Reference: Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html) and [Annotation-based Configuration](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config.html)
  Use for: stereotypes, bean names, scope. The annotation-config page is only an overview.
- [Spring Boot: @SpringBootApplication](https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html) and [Testing Spring Boot Applications](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)
  Use for: app entry point, @SpringBootTest, test slices, MockMvc. (Testing page is long; only the first half was read.)
- [Spring Framework: Annotated Controllers](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann.html) ([mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html), [arguments](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/arguments.html), [return values](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/return-types.html), [validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html), [exceptions](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html), [error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html))
  Use for: writing and extending controllers and error handling.
- [Spring Framework: @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
  Use for: rollback rules, readOnly, proxy self-invocation.
- [Spring Data JPA: repositories](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html), [query methods](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html), [persisting entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)
  Use for: repository interfaces, derived queries, save semantics.
- [Hibernate ORM Introduction](https://docs.hibernate.org/orm/current/introduction/html_single/)
  Use for: entity rules (@Entity, @Id, @Version). Very long page; only the entity chapter start was read, not @Column/@Embedded/@Enumerated.
- [Spring Boot How-to: Database Initialization (Flyway)](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
  Use for: migration naming and startup.
- [JUnit user guide: writing tests](https://docs.junit.org/current/writing-tests/test-classes-and-methods.html) ([assertions](https://docs.junit.org/current/writing-tests/assertions.html), [parameterized](https://docs.junit.org/current/writing-tests/parameterized-classes-and-tests.html))
  Use for: @Test, lifecycle, assertThrows, @ParameterizedTest.
- [BigDecimal (Java 21 API)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html) and [Oracle: Clock](https://docs.oracle.com/javase/tutorial/datetime/iso/clock.html)
  Use for: scale, equals vs compareTo, injectable time. (BigDecimal page is long; the first part was read.)
- [Spring Security reference: architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html), [authorizeHttpRequests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html), [JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [authentication architecture](https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html), [method security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
  Use for: filter chains, deny-by-default, SecurityContext, @PreAuthorize. (Method security page: first 100k characters read.)
- [Spring Framework: application events](https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html), [transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html), [task execution and @Async](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
  Use for: @EventListener, @TransactionalEventListener phases, @Async limits.
- [Spring Modulith: application events](https://docs.spring.io/spring-modulith/reference/events.html)
  Use for: @ApplicationModuleListener, the event publication registry, re-delivery.
- [Spring Data JPA: Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html)
  Use for: Specification, composition. The page shows findAll(spec) and a fluent paging API, not findAll(spec, pageable); the repo declares that method itself.
- [Spring Boot: externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), [profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html)
  Use for: property precedence, profile files, env var naming, @ConfigurationProperties. (Long page: validation and Duration/DataSize sections were not read.)
- [Spring Boot: actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
  Use for: health, probes, exposure, MeterRegistry, Prometheus. (Counters/timers are not documented on the metrics page excerpt.)
- [Micrometer Observation: introduction](https://docs.micrometer.io/micrometer/reference/observation/introduction.html), [instrumenting](https://docs.micrometer.io/micrometer/reference/observation/instrumenting.html)
  Use for: observations, handlers, low vs high cardinality. (openScope is not covered by the page.)
- [Spring Batch: chunk-oriented processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html), [domain](https://docs.spring.io/spring-batch/reference/domain.html)
  Use for: reader/processor/writer, commit interval, JobInstance, restart. (Skip configuration lives on a separate page, not read.)
- [GitHub Actions: workflow syntax](https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions) and [Docker Compose application model](https://docs.docker.com/compose/intro/compose-application-model/)
  Use for: triggers, jobs, concurrency, permissions; services, networks, volumes. (Workflow page: first part only; Compose page does not cover depends_on or profiles.)
- [Bruno: tests](https://docs.usebruno.com/testing/tests/introduction)
  Use for: Chai expect tests and bru.setVar. (The assert block and CLI are not on that page; see bruno/README.md.)
- [dev.java: Learn Java](https://dev.java/learn/)
  Oracle's current learning site (not yet verified in detail; page fetch failed). Use for: up-to-date follow-up reading.

## Wisdom (Communities)

None chosen yet. Ask the user before proposing any.

## Gaps
- Spring Batch skip configuration page, Docker Compose depends_on/profiles reference, Bruno CLI docs, OpenTelemetry/Grafana docs.
- AssertJ and Mockito official docs (the repo uses AssertJ; only JUnit's mention of it was sourced).
- `var`, `break`/`continue` and labelled loops sources still to be gathered.
- A trusted community for backend interview prep.
