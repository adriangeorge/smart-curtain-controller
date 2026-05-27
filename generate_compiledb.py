Import("env")

env.AddPostAction("buildprog", env.VerboseAction(
    lambda source, target, env: env.Execute("$PYTHONEXE -m platformio run --target compiledb"),
    "Regenerating compile_commands.json for clangd"
))
