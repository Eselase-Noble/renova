# Licensing

Renova is licensed with a file. The vendor signs it; Renova checks the signature on the customer's machine.
Nothing is sent anywhere, so it works on machines without internet access, which is where much legacy code is.

## What a licence gates

- **Assessing is free.** `analyze`, `portfolio` and everything the desktop app and the console show before a
  migration work without a licence. A prospect can see what Renova finds in their own code.
- **Migrating needs a licence** that covers the project's ecosystem and has not expired. This is checked in
  the engine, so it holds for the CLI, the desktop app, the web console and the IDE plugins alike.
- **A development build does not ask.** A version ending in `-SNAPSHOT`, which is what a build from source
  is, migrates without one. A release built from a version tag asks. `RENOVA_LICENCE_ENFORCE=true` makes a
  development build ask, for trying this out.

## What a licence says

| Field | Meaning | Enforced |
|---|---|---|
| Licensee | Who it was issued to | Shown |
| Edition | A name for what was sold: `trial`, `team`, ... | Shown |
| Ecosystems | `java`, `dotnet`, `php`, or `*` for every ecosystem, including ones added later | Yes |
| Expires | The last day it is valid | Yes |
| Seats | How many people may use it | No: stated in the file, not counted |

Seats are not counted because counting needs a server that Renova would have to call, and customers choose
Renova because it does not call one. The licence names its licensee in the app, and the contract says the rest.
A file can be copied; it cannot be edited or made up.

## For the customer

```sh
renova licence install acme.json     # checks the file and makes it this machine's licence
renova licence                       # what is installed and what it allows
```

In the desktop app: **Settings → Licence → Install licence file…**. On a server or a build agent, set
`RENOVA_LICENCE` to the file's path. The licence is otherwise kept as `licence.json` beside Renova's settings
(`~/.config/renova`, or `%APPDATA%\renova` on Windows).

Without a licence, or with one that does not fit, a migration stops before anything is copied and says why:

```
error: The licence of Acme Insurance covers java, not .NET (C#, Visual Basic). Assessing projects stays free. ...
```

## For the vendor

Licences are signed with a private key that exists in one place: `~/.config/renova/vendor/licence-signing.key`
on the vendor's machine. It is not in this repository and must never be. Its public half is
`engine/core/src/main/resources/renova-licence-key.pub`, which ships in every build.

**Back the private key up somewhere safe now.** If it is lost, no new licence can be issued for the builds
customers already have; if it leaks, anyone can issue licences. Replacing it means a new release and a new
licence file for every customer.

Issuing a licence (the commands are hidden from `--help`, and useless without the key):

```sh
# A 30-day trial of everything
renova licence issue --key ~/.config/renova/vendor/licence-signing.key \
    --licensee "Acme Insurance" --edition trial --days 30 -o acme-trial.json

# A year of Java and .NET for a team of ten
renova licence issue --key ~/.config/renova/vendor/licence-signing.key \
    --licensee "Acme Insurance" --edition team --ecosystems java,dotnet --seats 10 --expires 2027-10-07 -o acme.json
```

Send the customer the `.json` file. Each has a reference (`RNV-20261007-A374`) to keep with the contract.

## What this does not do

- **It does not stop a determined person.** Renova runs on the customer's machine; someone who patches the
  check out of it can. A licence file keeps honest customers honest and makes misuse deliberate.
- **No seat counting, no metering, no revocation.** A licence that should end early ends by expiring: issue
  short ones where that matters, and renew.
- **No self-service.** Licences are issued by hand with the command above; there is no shop or portal.
