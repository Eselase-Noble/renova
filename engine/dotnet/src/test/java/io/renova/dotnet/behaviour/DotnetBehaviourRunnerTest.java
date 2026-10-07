package io.renova.dotnet.behaviour;

import io.renova.core.behaviour.Route;
import io.renova.core.model.ProjectModel;
import io.renova.dotnet.DotnetPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Which .NET applications are run side by side and with which requests; nothing here needs Docker or the SDK. */
class DotnetBehaviourRunnerTest {

    private final DotnetBehaviourRunner runner = new DotnetBehaviourRunner();

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void findsAnAspNetCoreApplicationsRoutes(@TempDir Path root) throws Exception {
        write(root, "src/Orders.Api/Orders.Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><PropertyGroup>"
                + "<TargetFramework>netcoreapp3.1</TargetFramework></PropertyGroup></Project>");
        write(root, "src/Orders.Api/Controllers/OrdersController.cs", """
                [ApiController]
                [Route("api/[controller]")]
                public class OrdersController : ControllerBase
                {
                    [HttpGet]
                    public IEnumerable<Order> List() => null;

                    // [HttpGet("old")]
                    [HttpGet("{id:int}")]
                    public Order Get(int id) => null;

                    [HttpPost]
                    public Order Place(Order order) => order;

                    [HttpGet("/health")]
                    public string Health() => "ok";
                }
                """);
        write(root, "src/Orders.Api/Program.cs", "app.MapGet(\"/version\", () => \"1\");\n");
        write(root, "src/Orders.Api/obj/Generated.cs", "app.MapGet(\"/generated\", () => 1);\n");
        write(root, "tests/Orders.Tests/Orders.Tests.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                + "<TargetFramework>netcoreapp3.1</TargetFramework></PropertyGroup></Project>");
        ProjectModel model = new DotnetPlugin().model(root);

        assertThat(runner.unsupported(model)).isEmpty();
        assertThat(runner.routes(model, root)).extracting(Route::method, Route::template).containsExactlyInAnyOrder(
                tuple("GET", "/api/orders"), tuple("GET", "/api/orders/{id:int}"), tuple("POST", "/api/orders"),
                tuple("GET", "/health"), tuple("GET", "/version"));
        assertThat(runner.discover(model, root)).extracting(s -> s.steps().getFirst().path())
                .containsExactlyInAnyOrder("/", "/api/orders", "/api/orders/1", "/health", "/version");
        assertThat(runner.environment(model, Map.of("ConnectionStrings:Default", "Host=db")))
                .isEqualTo(Map.of("ConnectionStrings__Default", "Host=db"));
    }

    @Test
    void classicAspNetAndLibrariesAreNotRun(@TempDir Path root) throws Exception {
        Path library = root.resolve("library");
        write(library, "Billing.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net6.0</TargetFramework></PropertyGroup></Project>");
        assertThat(runner.unsupported(new DotnetPlugin().model(library))).get().asString().contains("no web application to run");

        Path classic = root.resolve("classic");
        write(classic, "Shop.Web.csproj", "<Project ToolsVersion=\"15.0\" xmlns=\"http://schemas.microsoft.com/developer/msbuild/2003\"><PropertyGroup>"
                + "<ProjectTypeGuids>{349c5851-65df-11da-9384-00065b846f21}</ProjectTypeGuids><TargetFrameworkVersion>v4.8</TargetFrameworkVersion>"
                + "</PropertyGroup></Project>");
        assertThat(runner.unsupported(new DotnetPlugin().model(classic))).get().asString().contains("IIS on Windows");
    }
}
