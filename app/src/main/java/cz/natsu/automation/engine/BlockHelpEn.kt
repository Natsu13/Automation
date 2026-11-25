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
        "home_card" to """
            A card on the app's home screen: title, text, icon, progress and up to two buttons. A card with the same name (id) is replaced.

            The time left in the text
            • {left} (or {zbyva}) in the title or text becomes the time left of the countdown: “1 h 5 min”, “20 min”, “45 s”
            • example: Done in {left}

            Countdown
            • For a time: 90, 1:30 or 1:00:00
            • Until a time: 18:30 (tomorrow when it has passed today)
            • the card shows a bar and “19:59 left” and stays after the run ends – with a countdown even after the app is closed
            • Remove the card when it ends – it goes by itself at the end

            What happens during the countdown (Plan a run)
            The card only counts down. Add actions with Plan a run blocks after this one – each block is one run, so you can have as many milestones as you like:
            • When the card has left 20:00 → an automation with a warning
            • At a progress of the card's countdown 90 → another one
            • When the card has left 0 → what happens at the end

            The automation that runs gets the Input you give the block – the card's name, say, so it can change the card with Edit card (“Hurry, only {left} left!”), or a text for a notification. The plans belong to the card: closing it, removing it or showing it with another countdown cancels them.

            Buttons
            • Answer – the block Wait for a card button returns its text
            • Stop this run – Cancel of a download, say
            • Run an automation – with the given Input (empty = the button's text); works also after the run ended
            • Just close the card
        """.trimIndent(),

        "home_card_edit" to """
            Changes only what you fill in – title, text, icon or progress. An empty field keeps the old value. The card's countdown, buttons and planned runs stay.

            • {left} (or {zbyva}) in the title or text shows the time left of the card's countdown
            • typical use: an automation started by Plan a run gets the card's name as Input and puts the Input variable into Card name

            Example: Text = Hurry, only {left} left!, Icon = warning
        """.trimIndent(),

        "schedule_run" to """
            Runs the chosen automation later, even while the app is closed. When:
            • After a time – 90, 1:30 or 1:00:00 from now
            • At a time – 18:30 (today, or tomorrow when it has passed)
            • When the card has left – 20:00 before the card's countdown ends
            • At a progress of the card's countdown – 90 = when 90 % of the time is gone

            The Input for the automation becomes its Input variable (the card's name, say, or a text for a notification). The trigger type is “Planned run”.

            More milestones = more of these blocks. A plan tied to a card goes when you close or remove the card or give it another countdown. The plan name is optional: a plan with the same name replaces the old one, and Cancel a planned run cancels it by it. The output is the time the automation will run.
        """.trimIndent(),

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
            • Double the pause every time – 2 s, 4 s, 8 s…, to give the server time to recover
            • On failure – steps that run after the last failed attempt
            • the output of the Try block is the error text (empty when all passed)

            Try can be nested and put into loops. With a Time limit block inside
            it makes "try this for 10 s at most, otherwise do something else".
        """.trimIndent(),

        "lock" to """
            The steps inside are done by one round or branch at a time. The
            others that reach the block wait until the one before them is done.

            Why: rounds running in parallel share variables. When two rounds read
            the sum 10 at once, both add their number and save, one addition is
            lost. Inside One at a time that cannot happen.

            Example – a sum from several APIs at once
            • For each item (in parallel)
            •     HTTP request, Get from JSON → price
            •     One at a time
            •         Calculate total + price
            •         Set variable total
            •     End of One at a time
            • End of loop

            Leave slow steps (HTTP request) outside – inside the rounds would wait
            for each other and running in parallel would be pointless. Only the
            short part that works with the shared variable belongs inside.

            Lock name: blocks with the same name take turns together, blocks with
            different names do not wait for each other. An empty name is one
            shared lock.

            For a single addition, appending a text or adding to a list the
            Change variable block is enough – it needs no lock.
        """.trimIndent(),

        "change_variable" to """
            Changes a variable based on what is in it – in one step.

            • Add a number – an empty variable counts as 0; subtract with a negative number
            • Append text to the end
            • Add an item to a list – the variable becomes a JSON array

            Unlike the pair "Calculate + Set variable" nothing fits between
            reading and saving, so it works in a parallel loop too: ten rounds can
            add to one sum at once and nothing gets lost.

            When you need several steps with the variable at once (read, decide,
            save), put them into the One at a time block.
        """.trimIndent(),

        "html_window" to """
            Your own page in a window of the app – HTML, CSS and JavaScript
            in the HTML field (or from a variable, e.g. from Read file).

            Page → automation
            • automation.call("action", data) returns a Promise of the answer
            • the steps inside the block run for every call, one at a time
            • the block's value is {"action": "action", "data": …} – take it
              apart with Value from JSON (path action, data.id…)
            • the answer is the result of the last step, or Return result
              (inside the window it only answers the page, the run goes on)
            • an error in the steps rejects the call: catch (e) → e.message
            • an answer that is JSON comes to the page as an object

            Automation → page
            • the Send to window block → automation.on("event", data => …)

            Closing
            • automation.close(value) – the value is the block's output then
            • the Close button or Back – the output is empty
            • the bar with Close is there with a title, or always with the switch

            Display
            • Over the whole screen, or From the bottom, half way
            • from the bottom: drag the handle at the top up for the whole
              screen, down to close it; a tap switches the size

            Safety
            • the page cannot see the phone's files; internet only when allowed
            • links open in the browser
            • No screenshots – the window cannot be captured or recorded
            • alert(), confirm() and prompt() work

            Example: the Secure notebook example.
        """.trimIndent(),

        "derive_key" to """
            A password is never used directly – a key is made from it (PBKDF2,
            SHA-256). It is slow on purpose, so guessing passwords takes ages.

            The salt is a random piece mixed into the password:
            • empty – a new salt (the first encryption)
            • an earlier encrypted text (enc1:…) – its salt is used, so the
              same password makes the same key as then

            Checking the password
            1. the first time, encrypt "ok" and keep it (the check)
            2. next time: Key from a password with the salt of the check
            3. Try { Decrypt the check } – an error = a wrong password

            The key (key1:…) is not shown in the run log. Do not store it
            unlocked – to remember it, use Lock with fingerprint.
        """.trimIndent(),

        "bio_lock" to """
            Locks a value with a key in the phone's secure chip. The phone
            never gives the key out and uses it only after your finger.

            Typically: after the password is typed, lock the Key from a
            password and keep the result with Store value. Next time:
            Try { Unlock with fingerprint } On failure { ask for the password }

            • a locked value (bio1:…) cannot be unlocked on another phone
            • after a new fingerprint is added in the phone's settings it stops
              working (an Android rule) – lock it again then
            • the lock name keeps keys of different automations apart
            • the Delete fingerprint lock block removes the key from the phone (tidying up)
        """.trimIndent(),

        "time_limit" to """
            The steps inside have to finish within the given time. When they do
            not, they are stopped and the block fails with "the steps did not
            finish".

            Catch the error with a Try block around it – and do something else in
            the On failure branch (try a backup server, report it…).

            While stepping through in the editor the limit does not count, so you
            can look at the steps in peace.
        """.trimIndent(),

        "location" to """
            Finds where the phone is right now (Wi-Fi, cell towers, GPS – whatever
            is at hand). When no new position comes within 15 s, the last known
            one is returned.

            • latitude,longitude – e.g. 50.075500,14.437800; put it straight into
              the link https://maps.google.com/?q=… or into geo:
            • JSON – {"lat":…, "lon":…, "accuracy":…}; accuracy is in metres,
              take the values with the Get from JSON block

            The Address from location block turns coordinates into an address.

            From events in the background it needs location allowed "All the time".
        """.trimIndent(),

        "qr_read" to """
            Finds a QR code or a bar code (EAN, Code 128…) in a picture and
            returns what it holds.

            Another block supplies the picture:
            • Take a photo – aim at the code and take the picture
            • Pick file / image – a screenshot or a photo from the gallery
            • the https://… address of a picture on the web

            The output is the text of the code: open a link with the Open link
            block, send a product number to an API…
        """.trimIndent(),

        "for_each" to """
            The list can be a JSON array ["a", "b"] or a text with one item per line.

            The output of the block is the current item. For an array of objects it is
            the whole object – take its parts with the Get from JSON block.

            Which round is running tells the Loop position block. End the loop or
            skip a round with the End loop / skip round block.

            In parallel: all rounds run at the same time (at most as many as you
            set) – ten API calls instead of waiting for one after another. Every
            round has its own item, its own Loop position and its own step
            outputs, so the rounds do not mix. Variables (Set variable) are
            shared though and the rounds overwrite them. Add up with the Change
            variable block, or put the "read, compute, save" steps into One at a
            time.

            Get the results with "Save the rounds' results to variable": after the
            end it holds a list with the output of the last step of every round,
            in the order of the items.

            Ending the loop in one round ends all of it: the other rounds stop
            and the steps after the loop hold the values of that round – "the
            first one to find it wins". An error in one round stops the others
            the same way and is an error of the whole loop; to let a round
            survive it, put its steps into Try. The pause between the starts of
            the rounds spreads requests in time when an API limits how many may
            come per second.
        """.trimIndent(),

        "repeat" to """
            The output of the block is the number of the round (from 1).

            In parallel: all rounds run at the same time (at most as many as you
            set) – ten API calls instead of waiting for one after another. Every
            round has its own item, its own Loop position and its own step
            outputs, so the rounds do not mix. Variables (Set variable) are
            shared though and the rounds overwrite them. Add up with the Change
            variable block, or put the "read, compute, save" steps into One at a
            time.

            Get the results with "Save the rounds' results to variable": after the
            end it holds a list with the output of the last step of every round,
            in the order of the items.

            Ending the loop in one round ends all of it: the other rounds stop
            and the steps after the loop hold the values of that round – "the
            first one to find it wins". An error in one round stops the others
            the same way and is an error of the whole loop; to let a round
            survive it, put its steps into Try. The pause between the starts of
            the rounds spreads requests in time when an API limits how many may
            come per second.
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
            • newer_version("v2.10", "2.9") → true, version_compare(a, b) → -1 / 0 / 1

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

            Groups of steps (like the Try, Parallel… blocks)
            • x = script … end – a nested script; its return ends only it
            • error = try(attempts, pause, double) … catch … end
            • parallel … branch … end – in a script the branches run one by one
            • lock("name") … end, time_limit(10) … end
            • call = html_window(html, title) … end – HTML window: the body
              answers every call of the page (call.action, call.data), its
              return goes to the page; after closing, call is the closing value
            Show as script writes the whole automation like that.

            Functions – images
            Each draws one thing and returns a new picture (a PNG file), so they
            chain and the result goes into a notification or another block. An
            image is a stored image, a file, an https:// address or a step's
            output. Coordinates are px from the top left; colours as elsewhere
            (green, #FF9800).
            • img_new(w, h, colour) – an empty canvas (transparent without a colour)
            • img_size(img) → {width, height}
            • img_scale(img, 0.7), img_resize(img, w, h), img_crop(img, x, y, w, h)
            • img_draw(canvas, img, x, y, w, h) – puts a picture in (w, h optional)
            • img_rect(img, x, y, w, h, colour, radius) – a filled rectangle
            • img_border(img, colour, width, radius) – a frame around it
            • img_line(img, x1, y1, x2, y2, colour, width)
            • img_circle(img, x, y, r, colour, width) – filled without a width
            • img_arc(img, x, y, r, percent, colour, width, back_colour) – a progress ring
            • img_text(img, text, x, y, size, colour, "left"/"center"/"right") – y is the middle of the text
            • img_tint(img, colour) – the whole shape one colour
            • img_fill(img, percent, colour, direction, rest_colour) – part of the
              shape in the colour by percent; direction "up" (default), "down", "left", "right"
            • img_bar(img, x, y, w, h, percent, colour, back_colour, radius) – a progress bar
            • a smaller icon with a bar under it:
              p = img_draw(img_new(192, 192), picture, 36, 0, 120)
              return img_bar(p, 16, 150, 160, 24, free, "green")

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
