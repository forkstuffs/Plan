/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.delivery.export;

import com.djrapitops.plan.delivery.rendering.json.JSONFactory;
import com.djrapitops.plan.delivery.rendering.pages.NetworkPage;
import com.djrapitops.plan.delivery.rendering.pages.PageFactory;
import com.djrapitops.plan.delivery.rendering.pages.PlayerPage;
import com.djrapitops.plan.exceptions.ParseException;
import com.djrapitops.plan.exceptions.database.DBOpException;
import com.djrapitops.plan.gathering.domain.BaseUser;
import com.djrapitops.plan.identification.Server;
import com.djrapitops.plan.identification.ServerInfo;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.ExportSettings;
import com.djrapitops.plan.settings.theme.Theme;
import com.djrapitops.plan.storage.database.DBSystem;
import com.djrapitops.plan.storage.database.Database;
import com.djrapitops.plan.storage.database.queries.objects.BaseUserQueries;
import com.djrapitops.plan.storage.database.queries.objects.ServerQueries;
import com.djrapitops.plan.storage.database.queries.objects.UserIdentifierQueries;
import com.djrapitops.plan.storage.file.PlanFiles;
import com.djrapitops.plugin.logging.L;
import com.djrapitops.plugin.logging.error.ErrorHandler;
import com.djrapitops.plugin.utilities.Verify;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Class responsible for Html Export task.
 *
 * @author Rsl1122
 */
@Singleton
@Deprecated
public class HtmlExport extends SpecificExport {

    private final PlanConfig config;
    private final Theme theme;
    private final PlanFiles files;
    private final DBSystem dbSystem;
    private final Exporter exporter;
    private final PageFactory pageFactory;
    private final ErrorHandler errorHandler;

    @Inject
    public HtmlExport(
            PlanFiles files,
            PlanConfig config,
            Theme theme,
            DBSystem dbSystem,
            Exporter exporter,
            PageFactory pageFactory,
            JSONFactory jsonFactory,
            ServerInfo serverInfo,
            ErrorHandler errorHandler
    ) {
        super(files, jsonFactory, serverInfo);
        this.config = config;
        this.theme = theme;
        this.files = files;
        this.dbSystem = dbSystem;
        this.exporter = exporter;
        this.pageFactory = pageFactory;
        this.errorHandler = errorHandler;
    }

    @Override
    protected String getPath() {
        return config.get(ExportSettings.HTML_EXPORT_PATH);
    }

    public void exportServer(Server server) {
        Database database = dbSystem.getDatabase();
//        boolean hasProxy = database.query(ServerQueries.fetchProxyServerInformation()).isPresent();
//        if (serverInfo.getServer().isNotProxy() && hasProxy) {
//            return;
//        }
        exporter.exportServerPage(server);
    }

    public void exportPlayerPage(UUID playerUUID) {
        Optional<String> name = dbSystem.getDatabase().query(UserIdentifierQueries.fetchPlayerNameOf(playerUUID));
        exportPlayerPage(playerUUID, name.orElse("Unknown"));
    }

    public void exportPlayerPage(UUID playerUUID, String playerName) {
        PlayerPage playerPage = pageFactory.playerPage(playerUUID);
        try {
            exportPlayerPage(playerName, playerPage.toHtml());
        } catch (ParseException | IOException e) {
            errorHandler.log(L.ERROR, this.getClass(), e);
        }
    }

    public void exportCachedPlayerPage(UUID playerUUID) {
        Database database = dbSystem.getDatabase();
        boolean hasProxy = database.query(ServerQueries.fetchProxyServerInformation()).isPresent();
        if (serverInfo.getServer().isNotProxy() && hasProxy) {
            return;
        }

        database.query(UserIdentifierQueries.fetchPlayerNameOf(playerUUID))
                .ifPresent(playerName -> {
                    try {
                        exportAvailablePlayerPage(playerUUID, playerName);
                    } catch (IOException e) {
                        errorHandler.log(L.WARN, this.getClass(), e);
                    }
                });
    }

    public void exportPlayersPage() {
        try {
            String html = pageFactory.playersPage().toHtml()
                    .replace("href=\"plugins/", "href=\"../plugins/")
                    .replace("href=\"css/", "href=\"../css/")
                    .replace("src=\"plugins/", "src=\"../plugins/")
                    .replace("src=\"js/", "src=\"../js/");
            List<String> lines = Arrays.asList(StringUtils.split(html, "\n"));

            File htmlLocation = new File(getFolder(), "players");
            Verify.isTrue(htmlLocation.exists() && htmlLocation.isDirectory() || htmlLocation.mkdirs(),
                    () -> new FileNotFoundException("Output folder could not be created at" + htmlLocation.getAbsolutePath()));
            File exportFile = new File(htmlLocation, "index.html");
            export(exportFile, lines);
        } catch (IOException | DBOpException | ParseException e) {
            errorHandler.log(L.WARN, this.getClass(), e);
        }
    }

    public void exportAvailablePlayers() {
        try {
            Collection<BaseUser> users = dbSystem.getDatabase().query(BaseUserQueries.fetchAllBaseUsers());
            for (BaseUser user : users) {
                exportAvailablePlayerPage(user.getUuid(), user.getName());
            }
        } catch (IOException | DBOpException e) {
            errorHandler.log(L.WARN, this.getClass(), e);
        }
    }

    public void exportNetworkPage() {
        if (serverInfo.getServer().isNotProxy()) {
            return;
        }

        NetworkPage networkPage = pageFactory.networkPage();
        // TODO
    }

    private void copyFromJar(String[] resources) {
        for (String resource : resources) {
            try {
                copyFromJar(resource);
            } catch (IOException e) {
                errorHandler.log(L.WARN, this.getClass(), e);
            }
        }
    }

    private void copyFromJar(String resource) throws IOException {
        List<String> lines = files.getCustomizableResourceOrDefault(resource).asLines();

        File to = new File(getFolder(), resource.replace("web/", "").replace("/", File.separator));
        File locationFolder = to.getParentFile();

        Verify.isTrue(locationFolder.exists() && locationFolder.isDirectory() || locationFolder.mkdirs(),
                () -> new FileNotFoundException("Output folder could not be created at" + locationFolder.getAbsolutePath()));

        if (to.exists()) {
            Files.delete(to.toPath());
            if (!to.createNewFile()) {
                return;
            }
        }

        export(to, lines);
    }

    public void exportAvailableServerPages() {
        for (Server server : dbSystem.getDatabase().query(ServerQueries.fetchPlanServerInformationCollection())) {
            exportServer(server);
        }
    }
}
