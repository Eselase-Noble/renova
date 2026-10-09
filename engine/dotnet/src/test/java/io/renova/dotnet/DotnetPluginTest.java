package io.renova.dotnet;

import io.renova.core.engine.Analyzer;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.dotnet.detect.NamespaceDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the plugin reads from C# and Visual Basic projects, and what the bundled playbooks find in them. */
class DotnetPluginTest {

    static final String OLD_LIBRARY = """
            <?xml version="1.0" encoding="utf-8"?>
            <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
              <PropertyGroup>
                <Configuration Condition=" '$(Configuration)' == '' ">Debug</Configuration>
                <OutputType>Library</OutputType>
                <RootNamespace>Acme.Billing</RootNamespace>
                <AssemblyName>Acme.Billing</AssemblyName>
                <TargetFrameworkVersion>v4.7.2</TargetFrameworkVersion>
              </PropertyGroup>
              <PropertyGroup Condition=" '$(Configuration)|$(Platform)' == 'Debug|AnyCPU' ">
                <OutputPath>bin\\Debug\\</OutputPath>
              </PropertyGroup>
              <ItemGroup>
                <Reference Include="Newtonsoft.Json, Version=12.0.0.0, Culture=neutral">
                  <HintPath>..\\packages\\Newtonsoft.Json.12.0.3\\lib\\net45\\Newtonsoft.Json.dll</HintPath>
                </Reference>
                <Reference Include="Vendor.Pricing">
                  <HintPath>..\\lib\\Vendor.Pricing.dll</HintPath>
                </Reference>
                <Reference Include="System" />
                <Reference Include="System.Configuration" />
              </ItemGroup>
              <ItemGroup>
                <Compile Include="Invoice.cs" />
                <Compile Include="Properties\\AssemblyInfo.cs" />
                <Compile Include="..\\Shared\\Version.cs"><Link>Version.cs</Link></Compile>
              </ItemGroup>
              <ItemGroup>
                <None Include="packages.config" />
                <None Include="rates.csv"><CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory></None>
                <EmbeddedResource Include="Templates\\reminder.txt" />
                <Folder Include="Empty\\" />
              </ItemGroup>
              <ItemGroup>
                <ProjectReference Include="..\\Core\\Core.csproj"><Project>{1}</Project><Name>Core</Name></ProjectReference>
              </ItemGroup>
              <Import Project="$(MSBuildToolsPath)\\Microsoft.CSharp.targets" />
            </Project>
            """;

    static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    static Path legacyProject(Path root) throws Exception {
        write(root, "Billing/Billing.csproj", OLD_LIBRARY);
        write(root, "Billing/packages.config", "<packages><package id=\"Newtonsoft.Json\" version=\"12.0.3\" targetFramework=\"net472\" /></packages>");
        write(root, "Billing/Invoice.cs", "using System.Configuration;\nusing Json = Newtonsoft.Json.JsonConvert;\nclass Invoice {}\n");
        write(root, "Billing/Properties/AssemblyInfo.cs", "[assembly: System.Reflection.AssemblyVersion(\"1.0.0.0\")]\n");
        write(root, "Billing/Abandoned.cs", "this never compiled\n");
        write(root, "Billing/obj/Debug/Generated.cs", "class Generated {}\n");
        write(root, "Billing.Tests/Billing.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup><TargetFramework>net6.0</TargetFramework></PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="2.4.1" />
                    <PackageReference Include="Microsoft.NET.Test.Sdk"><Version>16.5.0</Version></PackageReference>
                    <ProjectReference Include="..\\Billing\\Billing.csproj" />
                  </ItemGroup>
                </Project>
                """);
        write(root, "Contracts/Contracts.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                + "<TargetFramework>netstandard2.0</TargetFramework></PropertyGroup></Project>");
        return root;
    }

    @Test
    void readsBothProjectFormats(@TempDir Path root) throws Exception {
        ProjectModel model = new DotnetPlugin().model(legacyProject(root));

        assertThat(model.modules()).extracting(Module::name).containsExactly("Billing.Tests", "Billing", "Contracts");
        Module billing = model.modules().get(1);
        assertThat(billing.fact("sdkStyle")).isEqualTo(false);
        assertThat(billing.fact("targetFrameworks")).isEqualTo(List.of("net472"));
        assertThat(billing.fact("packages")).isEqualTo(List.of("Newtonsoft.Json:12.0.3"));
        assertThat(billing.fact("kind")).isEqualTo("library");
        Module tests = model.modules().getFirst();
        assertThat(tests.fact("test")).isEqualTo(true);
        assertThat(tests.fact("packages")).isEqualTo(List.of("xunit:2.4.1", "Microsoft.NET.Test.Sdk:16.5.0"));
        assertThat(model.facts().get("languages")).isEqualTo(List.of("C#"));
    }

    @Test
    void theBundledTargetFindsWhatHasToMove(@TempDir Path root) throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(legacyProject(root));
        assertThat(playbook.id()).isEqualTo("dotnet-to-10");
        assertThat(registry.playbooksFor(root)).extracting(Playbook::id).contains("dotnet-to-10", "dotnet-to-8");

        List<Finding> findings = new Analyzer(registry).analyze(root, playbook).findings();

        // The old-format project and the .NET 6 one move; the .NET Standard library already runs on the target.
        assertThat(findings).filteredOn(f -> f.ruleId().equals("target-framework")).extracting(Finding::file)
                .containsExactlyInAnyOrder("Billing/Billing.csproj", "Billing.Tests/Billing.Tests.csproj");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("sdk-style-project")).extracting(Finding::file)
                .containsExactly("Billing/Billing.csproj");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("xunit-2-9")).hasSize(1);
        assertThat(findings).filteredOn(f -> f.ruleId().equals("test-sdk")).hasSize(1);
        // Generated code under obj is never read.
        assertThat(findings).noneMatch(f -> f.file().contains("/obj/"));
    }

    @Test
    void classicAspNetAndWebFormsGoToAiAsAWhole(@TempDir Path root) throws Exception {
        String web = OLD_LIBRARY.replace("<OutputType>Library</OutputType>",
                "<OutputType>Library</OutputType><ProjectTypeGuids>{349c5851-65df-11da-9384-00065b846f21};{fae04ec0-301f-11d3-bf4b-00c04f79efbc}</ProjectTypeGuids>");
        write(root, "Shop.Web/Shop.Web.csproj", web);
        write(root, "Shop.Web/Controllers/HomeController.cs", "using System.Web.Mvc;\nclass HomeController {}\n");
        write(root, "Shop.Web/Views/Home/Index.cshtml", "<h1>Shop</h1>\n");
        write(root, "Shop.Web/Global.asax.cs", "class MvcApplication {}\n");
        write(root, "Shop.Web/Web.config", "<configuration />\n");
        write(root, "Shop.Web/obj/Debug/Temp.cs", "class Temp {}\n");
        write(root, "Intranet/Intranet.csproj", web);
        write(root, "Intranet/Default.aspx", "<%@ Page Language=\"C#\" %>\n");
        write(root, "Shop.Core/Shop.Core.csproj", OLD_LIBRARY);
        write(root, "Shop.Core/Price.cs", "class Price {}\n");
        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(root);

        List<Finding> findings = new Analyzer(registry).analyze(root, playbook).findings();

        // Everything the change has to touch, from this project only; the library is converted by itself.
        assertThat(findings).filteredOn(f -> f.ruleId().equals("aspnet-mvc")).extracting(Finding::file).containsExactlyInAnyOrder(
                "Shop.Web/Shop.Web.csproj", "Shop.Web/Controllers/HomeController.cs", "Shop.Web/Views/Home/Index.cshtml", "Shop.Web/Web.config",
                "Shop.Web/Global.asax.cs");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("aspnet-web-forms")).extracting(Finding::file)
                .containsExactlyInAnyOrder("Intranet/Intranet.csproj", "Intranet/Default.aspx");
        assertThat(findings).filteredOn(f -> f.ruleId().equals("sdk-style-project")).extracting(Finding::file)
                .containsExactly("Shop.Core/Shop.Core.csproj");
        assertThat(playbook.rules().stream().filter(r -> r.id().equals("aspnet-web-forms")).findFirst().orElseThrow().fix().strategy())
                .isEqualTo("ai");
    }

    @Test
    void readsNamespacesInBothLanguages() {
        assertThat(NamespaceDetector.namespace("using System.Web.Mvc;")).isEqualTo("System.Web.Mvc");
        assertThat(NamespaceDetector.namespace("    using static System.Math;")).isEqualTo("System.Math");
        assertThat(NamespaceDetector.namespace("using Json = Newtonsoft.Json.JsonConvert; // alias")).isEqualTo("Newtonsoft.Json.JsonConvert");
        assertThat(NamespaceDetector.namespace("global using System.Linq;")).isEqualTo("System.Linq");
        assertThat(NamespaceDetector.namespace("Imports System.Configuration")).isEqualTo("System.Configuration");
        assertThat(NamespaceDetector.namespace("@using Acme.Shop.Models")).isEqualTo("Acme.Shop.Models");
        assertThat(NamespaceDetector.namespace("using (var stream = Open())")).isNull();
        assertThat(NamespaceDetector.namespace("using var reader = new StreamReader(path);")).isNull();
    }

    @Test
    void knowsTestsAndTargetFrameworks() {
        DotnetPlugin plugin = new DotnetPlugin();
        assertThat(plugin.isTestFile("tests/Billing.Tests/InvoiceTests.cs")).isTrue();
        assertThat(plugin.isTestFile("BillingTests/Helpers.cs")).isTrue();
        assertThat(plugin.isTestFile("src/Billing/InvoiceTest.vb")).isTrue();
        assertThat(plugin.isTestFile("src/Billing/Invoice.cs")).isFalse();
        assertThat(plugin.isTestFile("src/Contests/Entry.cs")).isFalse();

        assertThat(Tfm.fromFrameworkVersion("v4.7.2")).isEqualTo("net472");
        assertThat(Tfm.below("net48", "8.0")).isTrue();
        assertThat(Tfm.below("netcoreapp3.1", "8.0")).isTrue();
        assertThat(Tfm.below("net6.0-windows", "8.0")).isTrue();
        assertThat(Tfm.below("net8.0", "8.0")).isFalse();
        assertThat(Tfm.below("net10.0", "8.0")).isFalse();
        assertThat(Tfm.below("netstandard2.0", "10.0")).isFalse();
        assertThat(Tfm.modern("10.0", Tfm.platform("net6.0-windows"))).isEqualTo("net10.0-windows");
    }
}
