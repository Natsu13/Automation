package cz.natsu.automation.engine

/** English versions of the help texts in [BlockHelp]. */
internal object BlockHelpEn {
    private val condition = """
        Simple notation: a value, a condition and what to compare with.

        Expression – the condition written as text, when you need more things
        at once. Insert variables with the { } button or write them by name
        (those from the Set variable block).

        • comparison: == != < <= > >=
        • joining: a and b, a or b, not a (also && / || / !)
        • numbers are compared as numbers: "10" > 9 holds
        • texts in quotes: state == "ok"
        • functions: contains(text, "x"), len(list) > 0, starts(url, "https")

        Examples
        • battery < 20 and not charging
        • state == "vip" or price > 500
        • len(response.body.items) == 0

        Branches: add Otherwise if (another condition) and Otherwise in the
        ⋮ menu. The whole language is described in the help of the Script block.
    """.trimIndent()

    val texts: Map<String, String> = mapOf(
        "math" to """
            Simple notation: two numbers and an operation (+ − × ÷, remainder).

            Expert – an expression written as text. Numbers can come from variables.

            Operations
            • + − addition, subtraction
            • * or × multiplication, / or : division
            • % remainder (10 % 3 = 1)
            • ^ power (2 ^ 10 = 1024)
            • parentheses ( ) set the order

            Functions (separate arguments with a semicolon ;)
            • round(x) and round(x; places) – rounding
            • floor(x) down, ceil(x) up
            • abs(x) absolute value, sqrt(x) square root
            • min(a; b; …), max(a; b; …)

            Numbers
            • decimal comma or point: 1,5 = 1.5
            • spaces between thousands are ignored: 1 000 000

            Examples
            • price * 1,21 → price with VAT
            • round(sum / count; 2) → average to 2 places
            • max(0; balance - payment)
            • (temperature * 9 / 5) + 32 → °C to °F
        """.trimIndent(),

        "json_get" to """
            The path says where in the JSON the value is.

            • name → the value of the key name
            • user.address.city → nested objects
            • items[0] → the first item of an array, items[-1] the last
            • items[*].url → url of all items (returns a list)
            • ["key with a space"] → a key with special characters
            • empty path → the whole JSON

            Objects and arrays are returned as JSON, so they can go to another
            Get from JSON, For each item or List operations block.

            In the response of an HTTP request the body is under body:
            body.items[0].title, the status code is status.
        """.trimIndent(),

        "json_build" to """
            Every line is key: value.

            • title: Hello → {"title": "Hello"}
            • user.id: 42 → a nested object {"user": {"id": 42}}
            • tags: ["a", "b"] → an array
            • done: true → true / false

            With the switch “Numbers and true / false as values” 42 is stored as
            a number, without it as the text "42".

            Array from lines: every line is one item.
        """.trimIndent(),

        "http_request" to """
            The result is an object:
            {
              "status": 200,
              "ok": true,
              "headers": { "content-type": … },
              "body": … (JSON as JSON, otherwise text),
              "file": "file://…" (only when saved to a file)
            }
            Take values from it with the Get from JSON block: status, body.items[0].url…

            Headers: one per line, Authorization: Bearer 🔑token
            (insert the key as a secret value, it never gets into an export).

            Body
            • JSON – JSON text or the output of the Create JSON block
            • Form – key=value lines
            • Multipart – key=value, a file as photo=@output of the Pick file block
            • A whole file – sends the file as the body

            On a 4xx / 5xx error the action ends, unless you turn off “Error when
            the server returns…” or put the step into a Try block.
        """.trimIndent(),

        "try" to """
            When a step inside fails (network, API, invalid JSON…), the action does not end.

            • Attempts in total – how many times to try the steps before giving up
            • Pause – how many seconds to wait before the next attempt
            • On failure – steps that run after the last failed attempt
            • the output of the Try block is the error text (empty when all passed)

            Try can be nested and put into loops.
        """.trimIndent(),

        "for_each" to """
            The list can be a JSON array ["a", "b"] or a text with one item per line.

            The output of the block is the current item. For an array of objects it is
            the whole object – take its parts with the Get from JSON block.

            Which round is running tells the Loop position block. End the loop or
            skip a round with the End loop / skip round block.
        """.trimIndent(),

        "list" to """
            A list is a JSON array or a text with one item per line.
            The resulting list is always a JSON array.

            • Item at position – counted from 1, -1 is the last
            • Split text – by a separator (empty = new line)
            • Sum, average, minimum, maximum – numbers only
            • Sort – numbers by value, text alphabetically
        """.trimIndent(),

        "store_set" to """
            The value stays stored after the action ends; the next run reads it
            with the Load value block.

            • Only for this automation (default) – every automation has its own,
              names like last_state do not overwrite each other
            • Shared – one value for all automations

            Stored values can be seen and edited in the automation's settings
            (shared ones in the app's Settings).
        """.trimIndent(),

        "type_of" to """
            Returns what kind of value it is:
            • number – 42, -1,5, 1e3
            • text – anything else
            • object – JSON {…}
            • list – JSON array […]
            • yes/no – true or false
            • empty – nothing, spaces or null

            Useful in an If condition: If type is equal to “list”…
        """.trimIndent(),

        "if" to condition,
        "else_if" to condition,
        "while" to condition,

        "send_intent" to """
            An intent is a message for another app. What exactly to send is in that
            app's documentation (look for “intent”, “Tasker” or “automation”).

            What to do
            • Open a screen – a specific window of the app (activity)
            • Start a service – background work; the app must allow starting it
              from outside (exported), otherwise Android returns an error
            • Start a foreground service – when Android refuses a plain start
            • Send a broadcast – a message the app waits for

            Fields
            • Package – com.example.app (can be picked from a list)
            • Class – the full name, or shortened .SyncService; the button under
              the field lists what the chosen app allows
            • Action – e.g. android.intent.action.VIEW
            • Data – an address: https://…, tel:123, spotify:track:…

            Parameters (extras), one per line
            • query=hello → text
            • count=5 → whole number, enabled=true → yes/no, ratio=1.5 → decimal
            • the type can be forced: id:long=5, name:string=123, x:float=1.5,
              y:double=2.5, n:int=1, on:bool=true

            Examples
            • Open a screen, action android.intent.action.VIEW,
              data geo:0,0?q=Prague → a map
            • Send a broadcast, the app's package, an action from its documentation
        """.trimIndent(),

        "script" to """
            A short program. The result of the block is the value after return, or
            the result of the last line. Variables are shared with the Set variable
            block: what the script sets, later steps see, and the other way round.
            Insert values of other steps with the { } button; the automation's
            input is input.

            Values
            • numbers 12 and 1.5 (decimal point), texts "hello" or 'hello'
            • true / false, null (an empty value)
            • a list [1, 2, 3], an object {name: "Jan", age: 30}
            • a text with JSON (an API response, say) acts as a list / object

            Operations
            • + - * / % (remainder) ^ (power), parentheses
            • & joins texts: "Hello " & name
            • == != < <= > >=
            • and, or, not (also &&, ||, !)

            Statements (English or Czech)
            • x = 5, x += 1, list[0] = "a", person.age = 31
            • if condition … else if … else … end
            • while condition … end
            • for item in list … end
            • break (ends the loop), continue (next round)
            • return value
            • # a note to the end of the line

            Reading data
            • list[0] the first, list[-1] the last
            • response.body.items[0].title

            Built-in variables
            • input, clipboard, trigger
            • event("wifi_name"), secret("name")

            Functions – text
            • len(x), upper(x), lower(x), trim(x)
            • replace(text, what, with), contains(text, what)
            • ==, contains, starts and ends ignore upper / lower case
              (like the If block); exact(a, b) compares exactly
            • starts(text, start), ends(text, end)
            • split(text, separator), join(list, separator)
            • substr(text, from, length), find(text, what)
            • match(text, regex), matches(text, regex)

            Functions – numbers
            • round(x, places), floor(x), ceil(x), abs(x), sqrt(x)
            • min(…), max(…), sum(list), avg(list)
            • num(text), int(x), random(from, to)
            • calc("1 000 * 1,21") – the calculator of the Calculate block

            Functions – lists and others
            • range(5) → 1 to 5, range(2, 4)
            • push(list, x), keys(object), values(object)
            • json(text), tojson(value), type(x)
            • iif(condition, a, b), date("d. M. yyyy"), now()
            • var("name with a space") – a variable that cannot be written by name

            Blocks as functions
            Every block can be called by its type. Write parameters by name in { },
            or one after another in the order they have in the block.
            • res = http_request({url: "https://api.example.com/items"})
            • toast("Done: " & len(res.body.items))
            • set_clipboard(result), vibrate(300)
            • ask_text({title: "Name?"}), open_app("Spotify")
            • block("replace", {source: t, find: "a", replacement: "b"}) – for
              blocks named like a function (replace, text, random)
            The automation's settings → Show as script writes your steps this
            way, so you can see every block's type and parameters.

            Example
            total = 0
            for p in response.body.items
                if p.price > 100
                    total += p.price
                end
            end
            return "Total " & round(total, 2)
        """.trimIndent(),
    )
}
