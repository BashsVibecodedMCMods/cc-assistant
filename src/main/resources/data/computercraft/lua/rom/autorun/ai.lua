-- Tab completion for the ai program.
if shell and ai then
    local completion = require("cc.shell.completion")
    shell.setCompletionFunction("rom/programs/ai.lua", completion.build(
        { completion.choice, { "link", "status", "help", "--no-tools", "--profile ", "--resume" } }
    ))
end
