# Renova in one page

**Renova moves legacy Java, .NET and PHP applications to current platforms on the customer's own machines,
and proves the result by building it and running its tests.**

## The problem

Java 8 and Java EE applications, .NET Framework applications, Spring Boot 2, Struts, ASP.NET MVC 5, PHP 5 and
7, old Laravel: they run
the business and sit on platforms that are out of support or close to it. A platform upgrade touches thousands
of files. Most edits are mechanical; a few need judgement and take most of the time. Teams put it off because
nobody can say how big it is or whether the result still works.

## What Renova does

1. **Assesses** a project without changing it: every affected place, by category and severity, and the share
   that needs no human decision. A whole folder of projects is ranked, easiest first.
2. **Migrates a copy.** The original is never touched. Each stage is a git commit, so every change can be
   read, audited or reverted.
3. **Uses the right tool for each change:** deterministic rewrites for the mechanical bulk, an AI model for
   the changes that depend on context, and written guidance for what is left to a person.
4. **Verifies.** The migrated project is built and its own tests are run. A build that passes without running
   the tests is reported as failed. For web applications, the original and the migrated version can be run side
   by side and their answers compared.

## Why a customer can say yes

- **Code stays on their machine.** Desktop application, command line, or their own server. Not a hosted service.
- **Their own AI key, or none.** AI is optional. With it, each request and answer is logged; token use is
  reported per migration. An on-premises model works through any OpenAI-compatible server.
- **Nothing is hidden.** The report lists what was changed, what was not, and what a person still has to do.

## What it covers today

| From | To | How |
|---|---|---|
| Java 8, 11, 17 | Java 17, 21, 25 | No AI |
| Java EE (javax), Spring 5 | Jakarta EE 10 or 11, Spring 6 or 7 | No AI |
| Spring Boot 2 or 3 | Spring Boot 3.5 or 4.1 | No AI |
| Micronaut 2 or 3, Quarkus 1 or 2 | Micronaut 4, Quarkus 3 | No AI |
| Java EE on an application server | Spring Boot | No AI for the standard APIs |
| Ant projects | A Maven build, then any of the above | No AI |
| Struts 1, Jersey 1, Commons HttpClient 3 | Spring MVC, Jersey 3, HttpClient 5 | With AI |
| iBATIS 2, Hibernate's legacy Criteria | MyBatis 3, the JPA Criteria API on Hibernate 6 | With AI |
| .NET Framework, .NET Core, .NET 5 to 9 (C# and Visual Basic) | .NET 10 or 8 | No AI |
| ASP.NET MVC 5 and Web API 2 | ASP.NET Core | With AI |
| PHP 5.4 to 8.2 (Composer projects, and sites without Composer) | PHP 8.3, 8.4 or 8.5 | No AI |
| Laravel 7, 8, 9, 10 and later | Laravel 11, 12 or 13 | No AI |
| Symfony 4, 5, 6, 7 | Symfony 6.4, 7.4 or 8.1 | No AI for code and packages |

Maven and Gradle; Kotlin on the JVM; JUnit 4 to 5, Mockito, Log4j, NUnit 2 to 3 and other library upgrades.

## Evidence

Every row of this table is a migration that was run end to end and can be repeated; the full list, with what
was checked and what was not, is in [verified-migrations.md](verified-migrations.md).

| Project | Result |
|---|---|
| Spring PetClinic (public), Spring Boot 2.7 → 3.5 and → 4.1, Maven and Gradle | Builds, 41 tests pass, no AI |
| Spring PetClinic Microservices (public, seven modules), Spring Boot 2.6 → 3.5 | Six modules without AI; all seven with AI |
| Stateless (public .NET library, 2016), .NET Framework and NUnit 2 → .NET 10 | Builds, 73 tests pass, no AI |
| A Struts 1 application → Spring MVC | Builds; same answers as the original on 6 of 9 requests, the other 3 differ in form markup only |
| A Jersey 1 application → Jersey 3 | Builds, 9 tests pass, same answers as the original on 6 of 6 requests |
| An iBATIS 2 data layer → MyBatis 3; Hibernate legacy Criteria queries → Hibernate 6 | Each builds and passes its 4 unchanged tests |
| An ASP.NET MVC 5 application → ASP.NET Core on .NET 10 | Builds and runs; pages, routes, API, validation and anti-forgery checked by hand |
| FastRoute 1.3 (public PHP library, written for PHP 5.4), → PHP 8.4 | Installs, 204 tests pass, no AI |
| Laravel 8 and Laravel 10 applications → Laravel 13 on PHP 8.4 | 7 tests pass each, no AI; Laravel 8 beside 13: same answers to 8 of 9 requests, the ninth being the page that prints the version |
| A Laravel 7 application (closure factories, PHP 7.4) → Laravel 13 on PHP 8.4 | 6 tests pass, no AI; same answers as the original to 9 of 9 requests |
| An ASP.NET Core 3.1 API → .NET 10 | 4 tests pass, no AI; same answers as the original to 9 of 9 requests |
| 21 Java, 5 .NET and 16 PHP paths in the benchmark suites | All pass without AI |

## What it does not do yet

Said plainly, because a buyer will ask:

- The AI-assisted results above are each one small application, run once. Large Struts or MVC applications
  have not been tried.
- .NET: Web Forms, WCF services and Entity Framework 6 → EF Core are reported, not migrated. Windows Forms and
  WPF projects are converted and have not been built (that needs Windows). The original .NET Framework
  applications could not be run here, so .NET results are checked by build and tests, not against the original.
- Java: EJB 2, JAX-RPC, Faces managed beans and iBATIS are planned with the mapping written out and have not
  been run. Nothing larger than seven modules has been tried.
- PHP: the Symfony result is a skeleton with one controller, without security or Doctrine; one AI repair has
  been run, on a small library. The Laravel
  results are Laravel's own skeleton with one feature added, not a business application.
- Licences are issued by hand as signed files; there is no shop or portal, and seats are stated, not counted.
- The installers are not code-signed yet, so Windows and macOS warn when they are opened. Single sign-on is
  not built.
