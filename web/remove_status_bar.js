const fs = require('fs');
let content = fs.readFileSync('index.html', 'utf8');

const regex = /<div class="status-bar[^>]*>[\s\S]*?<\/div>\s*<\/div>/g;
content = content.replace(regex, '');

fs.writeFileSync('index.html', content);
console.log('Status bars properly removed.');
