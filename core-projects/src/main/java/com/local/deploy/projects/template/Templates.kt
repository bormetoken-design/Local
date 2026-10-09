package com.local.deploy.projects.template

import com.local.deploy.model.RuntimeType
import java.io.File

data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val runtimeType: RuntimeType,
    val files: Map<String, String>, // relative path -> content
    val startCommand: String,
    val defaultPort: Int = 8080
)

object ProjectTemplates {

    val allTemplates: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "discord-js-bot",
            name = "Discord.js Bot",
            description = "A starter template for Discord bots using Node.js and discord.js v14.",
            runtimeType = RuntimeType.NODEJS,
            startCommand = "node index.js",
            files = mapOf(
                "package.json" to """{
  "name": "discord-bot",
  "version": "1.0.0",
  "main": "index.js",
  "scripts": {
    "start": "node index.js"
  },
  "dependencies": {
    "discord.js": "^14.15.3",
    "dotenv": "^16.4.5"
  }
}""",
                "index.js" to """require('dotenv').config();
const { Client, GatewayIntentBits } = require('discord.js');

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent]
});

client.once('ready', () => {
  console.log(`[BOT READY] Logged in as ${'$'}{client.user.tag}`);
});

client.on('messageCreate', (message) => {
  if (message.author.bot) return;
  if (message.content === '!ping') {
    message.reply('Pong! Running smoothly on Android Local Deploy.');
  }
});

const token = process.env.DISCORD_TOKEN;
if (!token) {
  console.error('[ERROR] DISCORD_TOKEN is missing in .env file!');
  process.exit(1);
}

client.login(token);
""",
                ".env.example" to "DISCORD_TOKEN=your_bot_token_here\n"
            )
        ),

        ProjectTemplate(
            id = "discord-py-bot",
            name = "Discord.py Bot",
            description = "A starter template for Discord bots using Python and discord.py.",
            runtimeType = RuntimeType.PYTHON,
            startCommand = "python3 main.py",
            files = mapOf(
                "requirements.txt" to """discord.py>=2.3.2
python-dotenv>=1.0.1
""",
                "main.py" to """import os
import discord
from dotenv import load_dotenv

load_dotenv()

intents = discord.Intents.default()
intents.message_content = True

client = discord.Client(intents=intents)

@client.event
async def on_ready():
    print(f'[BOT READY] Logged in as {client.user}')

@client.event
async def on_message(message):
    if message.author == client.user:
        return
    if message.content == '!ping':
        await message.channel.send('Pong from Android Local Deploy!')

token = os.getenv('DISCORD_TOKEN')
if not token:
    print('[ERROR] DISCORD_TOKEN is missing in .env!')
    exit(1)

client.run(token)
""",
                ".env.example" to "DISCORD_TOKEN=your_bot_token_here\n"
            )
        ),

        ProjectTemplate(
            id = "php-web",
            name = "PHP Web Application",
            description = "Lightweight PHP website template with database connectivity helper.",
            runtimeType = RuntimeType.PHP,
            startCommand = "php -S 127.0.0.1:{PORT} -t .",
            defaultPort = 8080,
            files = mapOf(
                "index.php" to """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Local Deploy PHP App</title>
    <style>
        body { font-family: sans-serif; margin: 40px; background: #f8fafc; color: #1e293b; }
        .card { background: white; padding: 24px; border-radius: 12px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); max-width: 600px; margin: auto; }
        h1 { color: #2563eb; }
        .badge { display: inline-block; background: #e0e7ff; color: #3730a3; padding: 4px 12px; border-radius: 99px; font-size: 14px; font-weight: bold; }
    </style>
</head>
<body>
    <div class="card">
        <span class="badge">PHP <?php echo phpversion(); ?></span>
        <h1>Local Deploy Server</h1>
        <p>Your PHP application is running locally on Android!</p>
        <hr>
        <p><strong>Server Time:</strong> <?php echo date('Y-m-d H:i:s'); ?></p>
        <p><strong>Port:</strong> <?php echo ${'$'}_SERVER['SERVER_PORT']; ?></p>
    </div>
</body>
</html>
"""
            )
        ),

        ProjectTemplate(
            id = "static-web",
            name = "Static Website",
            description = "High-speed static website served through Caddy reverse proxy.",
            runtimeType = RuntimeType.STATIC,
            startCommand = "caddy file-server --listen 127.0.0.1:{PORT} --root .",
            defaultPort = 8080,
            files = mapOf(
                "index.html" to """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Welcome to Local Deploy</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; background: #0f172a; color: white; }
        .box { text-align: center; background: #1e293b; padding: 40px 60px; border-radius: 20px; border: 1px solid #334155; }
        h1 { color: #38bdf8; margin-bottom: 8px; }
        p { color: #94a3b8; font-size: 18px; }
    </style>
</head>
<body>
    <div class="box">
        <h1>Local Deploy Manager</h1>
        <p>Your static site is successfully hosted locally.</p>
    </div>
</body>
</html>
"""
            )
        )
    )

    fun applyTemplate(templateId: String, destinationDir: File): Boolean {
        val template = allTemplates.firstOrNull { it.id == templateId } ?: return false
        destinationDir.mkdirs()
        template.files.forEach { (relativePath, content) ->
            val file = File(destinationDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return true
    }
}
