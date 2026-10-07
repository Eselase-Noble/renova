package io.renova.dotnet.fix;

import io.renova.core.engine.BuildError;
import io.renova.dotnet.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectConverterTest {

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void anOldProjectBecomesAnSdkStyleOneThatCompilesTheSameFiles(@TempDir Path root) throws Exception {
        write(root, "Billing/Billing.csproj", io.renova.dotnet.DotnetPluginTestAccess.OLD_LIBRARY);
        write(root, "Billing/packages.config", "<packages><package id=\"Newtonsoft.Json\" version=\"12.0.3\" /></packages>");
        write(root, "Billing/Invoice.cs", "class Invoice {}\n");
        write(root, "Billing/Properties/AssemblyInfo.cs", "\n");
        write(root, "Billing/Abandoned.cs", "this never compiled\n");
        write(root, "Billing/Tools/Tools.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\" />");
        write(root, "Billing/Tools/Program.cs", "class Program {}\n");
        write(root, "Billing/obj/Debug/Generated.cs", "class Generated {}\n");

        ProjectConverter.Converted converted = ProjectConverter.convert(ProjectFile.read(root.resolve("Billing/Billing.csproj")),
                Map.of("system.configuration", "System.Configuration.ConfigurationManager:10.0.12"));

        assertThat(converted.xml()).isEqualTo("""
                <Project Sdk="Microsoft.NET.Sdk">

                  <PropertyGroup>
                    <TargetFramework>net472</TargetFramework>
                    <RootNamespace>Acme.Billing</RootNamespace>
                    <AssemblyName>Acme.Billing</AssemblyName>
                    <GenerateAssemblyInfo>false</GenerateAssemblyInfo>
                  </PropertyGroup>

                  <ItemGroup>
                    <PackageReference Include="Newtonsoft.Json" Version="12.0.3" />
                    <PackageReference Include="System.Configuration.ConfigurationManager" Version="10.0.12" />
                  </ItemGroup>

                  <ItemGroup>
                    <ProjectReference Include="..\\Core\\Core.csproj" />
                  </ItemGroup>

                  <ItemGroup>
                    <Reference Include="Vendor.Pricing">
                      <HintPath>..\\lib\\Vendor.Pricing.dll</HintPath>
                    </Reference>
                  </ItemGroup>

                  <ItemGroup>
                    <Compile Remove="Abandoned.cs" />
                    <Compile Remove="Tools\\**" />
                    <Compile Include="..\\Shared\\Version.cs">
                      <Link>Version.cs</Link>
                    </Compile>
                    <None Update="rates.csv">
                      <CopyToOutputDirectory>PreserveNewest</CopyToOutputDirectory>
                    </None>
                    <EmbeddedResource Include="Templates\\reminder.txt" />
                  </ItemGroup>

                </Project>
                """);
        assertThat(converted.notes()).anyMatch(n -> n.contains("Vendor.Pricing")).anyMatch(n -> n.contains("Abandoned.cs"));
    }

    @Test
    void editsAnSdkStyleProjectInPlace() {
        String xml = """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFrameworks>net48;netstandard2.0;netcoreapp3.1</TargetFrameworks>
                  </PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="xunit" Version="2.4.1" />
                    <PackageReference Include="coverlet.collector" Version="1.2.0">
                      <PrivateAssets>all</PrivateAssets>
                    </PackageReference>
                  </ItemGroup>
                </Project>
                """;
        String moved = ProjectXml.setTargetFramework(xml, "10.0");
        assertThat(moved).contains("<TargetFrameworks>net10.0;netstandard2.0</TargetFrameworks>");
        assertThat(ProjectXml.setTargetFramework("<UseWPF>true</UseWPF><TargetFramework>net472</TargetFramework>", "8.0"))
                .contains("<TargetFramework>net8.0-windows</TargetFramework>");

        String bumped = ProjectXml.setPackageVersion(xml, "XUnit", "2.9.3");
        assertThat(bumped).contains("<PackageReference Include=\"xunit\" Version=\"2.9.3\" />");

        String added = ProjectXml.addPackage(xml, "Microsoft.NET.Test.Sdk", "18.10.1");
        assertThat(added).contains("""
                    </PackageReference>
                    <PackageReference Include="Microsoft.NET.Test.Sdk" Version="18.10.1" />
                  </ItemGroup>
                """);
        assertThat(ProjectXml.addPackage(added, "microsoft.net.test.sdk", "1.0")).isEqualTo(added);
        assertThat(ProjectXml.addPackage("<Project Sdk=\"Microsoft.NET.Sdk\">\n</Project>\n", "A", "1")).isEqualTo("""
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <PackageReference Include="A" Version="1" />
                  </ItemGroup>

                </Project>
                """);

        String removed = ProjectXml.removePackage(xml, "coverlet.collector");
        assertThat(removed).doesNotContain("coverlet").doesNotContain("PrivateAssets").contains("xunit");
        assertThat(ProjectXml.setProperty(xml, "Nullable", "disable")).contains("    <Nullable>disable</Nullable>\n  </PropertyGroup>");
    }

    @Test
    void readsCompilerNuGetAndToolErrors() {
        Path ws = Path.of("/work/ws");
        String output = """
                /work/ws/src/Billing/Invoice.cs(12,34): error CS0246: The type or namespace name 'ConfigurationManager' could not be found (are you missing a using directive or an assembly reference?) [/work/ws/src/Billing/Billing.csproj]
                /work/ws/src/Billing/Invoice.cs(12,34): error CS0246: The type or namespace name 'ConfigurationManager' could not be found (are you missing a using directive or an assembly reference?) [/work/ws/src/Billing/Billing.csproj]
                /work/ws/Ledger/Account.vb(7,13): error BC30002: Type 'SqlConnection' is not defined. [/work/ws/Ledger/Ledger.vbproj]
                /work/ws/tests/T/T.csproj : error NU1202: Package NUnit 2.6.4 is not compatible with net10.0 (.NETCoreApp,Version=v10.0). [/work/ws/All.sln]
                vbc : error BC30002: Type 'Global.Microsoft.VisualBasic.Devices.Computer' is not defined. [/work/ws/Ledger/Ledger.vbproj]
                /work/ws/src/Billing/Old.cs(3,1): warning CS0618: 'X' is obsolete [/work/ws/src/Billing/Billing.csproj]
                """;
        List<BuildError> errors = DotnetVerifier.parse(output, ws);
        assertThat(errors).extracting(BuildError::file, BuildError::line).containsExactly(
                org.assertj.core.groups.Tuple.tuple("src/Billing/Invoice.cs", 12),
                org.assertj.core.groups.Tuple.tuple("Ledger/Account.vb", 7),
                org.assertj.core.groups.Tuple.tuple("tests/T/T.csproj", 0),
                org.assertj.core.groups.Tuple.tuple("Ledger/Ledger.vbproj", 0));
        assertThat(errors.getFirst().message()).startsWith("CS0246: The type or namespace name 'ConfigurationManager'");
        assertThat(errors.get(2).message()).startsWith("NU1202: Package NUnit 2.6.4 is not compatible");
    }

    @Test
    void readsTestResultsAndBlamesTheProjectCodeThatThrew(@TempDir Path ws) throws Exception {
        write(ws, ".renova/test-results/1/run.trx", """
                <TestRun><Results>
                  <UnitTestResult testName="Acme.Billing.Tests.TaxTests.Rounds" outcome="Passed" />
                  <UnitTestResult testName="Acme.Billing.Tests.TaxTests.ReadsTheRate" outcome="Failed">
                    <Output><ErrorInfo>
                      <Message>System.PlatformNotSupportedException : Thread abort is not supported on this platform.</Message>
                      <StackTrace>   at System.Threading.Thread.Abort()
                   at Acme.Billing.Worker.Stop() in %s/src/Billing/Worker.cs:line 31
                   at Acme.Billing.Tests.TaxTests.ReadsTheRate() in %s/tests/Billing.Tests/TaxTests.cs:line 18</StackTrace>
                    </ErrorInfo></Output>
                  </UnitTestResult>
                </Results></TestRun>
                """.formatted(ws.toAbsolutePath(), ws.toAbsolutePath()));

        DotnetVerifier.TestRun run = DotnetVerifier.testResults(ws.resolve(".renova/test-results/1"), ws);

        assertThat(run.ran()).isEqualTo(2);
        assertThat(run.failures()).containsExactly(new BuildError("src/Billing/Worker.cs", 31,
                "test Acme.Billing.Tests.TaxTests.ReadsTheRate failed: System.PlatformNotSupportedException : Thread abort is not supported on this platform."));
        assertThat(run.failures().getFirst().fromFailedTest()).isTrue();
    }

    @Test
    void aWpfProjectLeavesItsXamlToTheSdkAndStaysOnWindows(@TempDir Path root) throws Exception {
        write(root, "Notes/Notes.csproj", """
                <Project ToolsVersion="15.0" xmlns="http://schemas.microsoft.com/developer/msbuild/2003">
                  <PropertyGroup>
                    <OutputType>WinExe</OutputType>
                    <TargetFrameworkVersion>v4.8</TargetFrameworkVersion>
                  </PropertyGroup>
                  <ItemGroup>
                    <Reference Include="PresentationFramework" />
                    <Reference Include="System.Xaml" />
                  </ItemGroup>
                  <ItemGroup>
                    <ApplicationDefinition Include="App.xaml"><Generator>MSBuild:Compile</Generator></ApplicationDefinition>
                    <Page Include="MainWindow.xaml"><Generator>MSBuild:Compile</Generator><SubType>Designer</SubType></Page>
                    <Compile Include="MainWindow.xaml.cs"><DependentUpon>MainWindow.xaml</DependentUpon><SubType>Code</SubType></Compile>
                    <Resource Include="Images\\logo.png" />
                  </ItemGroup>
                </Project>
                """);
        write(root, "Notes/MainWindow.xaml.cs", "class MainWindow {}\n");

        String xml = ProjectConverter.convert(ProjectFile.read(root.resolve("Notes/Notes.csproj")), Map.of()).xml();

        assertThat(xml).contains("<UseWPF>true</UseWPF>", "<OutputType>WinExe</OutputType>", "<Resource Include=\"Images\\logo.png\" />")
                .doesNotContain("<Page ", "ApplicationDefinition");
        assertThat(ProjectXml.setTargetFramework(xml, "10.0")).contains("<TargetFramework>net10.0-windows</TargetFramework>");
        // A project that refers to it has to follow it to Windows; one already there, or on .NET Standard, is left.
        assertThat(ProjectXml.targetWindows("<TargetFramework>net10.0</TargetFramework>")).isEqualTo("<TargetFramework>net10.0-windows</TargetFramework>");
        assertThat(ProjectXml.targetWindows("<TargetFrameworks>net10.0-windows;netstandard2.0</TargetFrameworks>"))
                .isEqualTo("<TargetFrameworks>net10.0-windows;netstandard2.0</TargetFrameworks>");
    }
}
